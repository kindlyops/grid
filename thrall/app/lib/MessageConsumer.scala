package lib

import java.util.concurrent.atomic.{AtomicReference, AtomicLong}

import com.amazonaws.services.cloudwatch.model.Dimension
import org.elasticsearch.action.deletebyquery.DeleteByQueryResponse
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration._

import com.amazonaws.services.sqs.AmazonSQSClient
import com.amazonaws.services.sqs.model.{Message => SQSMessage, DeleteMessageRequest, ReceiveMessageRequest}
import org.elasticsearch.action.update.UpdateResponse
import _root_.play.api.libs.json._
import _root_.play.api.libs.functional.syntax._
import akka.actor.ActorSystem
import scalaz.syntax.id._

import com.gu.mediaservice.lib.json.PlayJsonHelpers._
import java.util.concurrent.Executors


object MessageConsumer {

  val actorSystem = ActorSystem("MessageConsumer")

  val timeMessageLastProcessed = new AtomicReference[DateTime](DateTime.now)

  private implicit val ctx: ExecutionContext =
    ExecutionContext.fromExecutor(Executors.newCachedThreadPool)

  def startSchedule(): Unit =
    actorSystem.scheduler.scheduleOnce(0.seconds)(processMessages())

  lazy val client =
    new AmazonSQSClient(Config.awsCredentials) <| (_ setEndpoint Config.awsEndpoint)

  @tailrec
  def processMessages() {
    // Pull 1 message at a time to avoid starvation
    // Wait for maximum duration (20s) as per doc recommendation:
    // http://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-long-polling.html
    for (msg <- getMessages(waitTime = 20, maxMessages = 1)) {
      val future = for {
        message <- Future(extractSNSMessage(msg) getOrElse sys.error("Invalid message structure (not via SNS?)"))
        processor = message.subject.flatMap(chooseProcessor)
        _ <- processor.fold(
          sys.error(s"Unrecognised message subject ${message.subject}"))(
          _.apply(message.body))
        _ = recordMessageLatency(message)
        _ = timeMessageLastProcessed.lazySet(DateTime.now)
      } yield ()
      future |> deleteOnSuccess(msg)
    }

    processMessages()
  }

  def recordMessageLatency(message: SNSMessage) = {
    val latency = DateTime.now.getMillis - message.timestamp.getMillis
    val dimensions = message.subject match {
      case Some(subject) => List(new Dimension().withName("subject").withValue(subject))
      case None          => List()
    }
    ThrallMetrics.processingLatency.runRecordOne(latency, dimensions)
  }

  def chooseProcessor(subject: String): Option[JsValue => Future[Any]] =
    PartialFunction.condOpt(subject) {
      case "image"                      => indexImage
      case "delete-image"               => deleteImage
      case "update-image"               => indexImage
      case "update-image-exports"       => updateImageExports
      case "update-image-user-metadata" => updateImageUserMetadata
      case "heartbeat"                  => heartbeat
    }

  def heartbeat(msg: JsValue) = Future {
    None
  }

  def deleteOnSuccess(msg: SQSMessage)(f: Future[Any]): Unit =
    f.onSuccess { case _ => deleteMessage(msg) }

  def getMessages(waitTime: Int, maxMessages: Int): Seq[SQSMessage] =
    client.receiveMessage(
      new ReceiveMessageRequest(Config.queueUrl)
        .withWaitTimeSeconds(waitTime)
        .withMaxNumberOfMessages(maxMessages)
    ).getMessages.asScala.toList

  def extractSNSMessage(sqsMessage: SQSMessage): Option[SNSMessage] =
    Json.fromJson[SNSMessage](Json.parse(sqsMessage.getBody)) <| logParseErrors |> (_.asOpt)

  def indexImage(image: JsValue): Future[UpdateResponse] =
    withImageId(image)(id => ElasticSearch.indexImage(id, image))

  def updateImageExports(exports: JsValue): Future[UpdateResponse] =
    withImageId(exports)(id => ElasticSearch.updateImageExports(id, exports \ "data"))

  def updateImageUserMetadata(metadata: JsValue): Future[UpdateResponse] =
    withImageId(metadata)(id => ElasticSearch.applyImageMetadataOverride(id, metadata \ "data"))

  def deleteImage(image: JsValue): Future[EsResponse] =
    withImageId(image) { id =>
      // if we cannot delete the image as it's "protected", succeed and delete
      // the message anyway.
      ElasticSearch.deleteImage(id).map {
        case r: DeleteByQueryResponse => {
          ImageStore.deleteOriginal(id)
          ImageStore.deleteThumbnail(id)
          EsResponse(s"Image deleted: $id")
        }
      } recoverWith {
        case ImageNotDeletable => {
          Future.successful(EsResponse(s"Image cannot be deleted: $id"))
        }
      }
    }

  def withImageId[A](image: JsValue)(f: String => A): A =
    image \ "id" match {
      case JsString(id) => f(id)
      case _            => sys.error(s"No id field present in message body: $image")
    }

  def deleteMessage(message: SQSMessage): Unit =
    client.deleteMessage(new DeleteMessageRequest(Config.queueUrl, message.getReceiptHandle))

}

// TODO: improve and use this (for logging especially) else where.
case class EsResponse(message: String)

case class SNSMessage(
  messageType: String,
  messageId: String,
  topicArn: String,
  subject: Option[String],
  timestamp: DateTime,
  body: JsValue
)

object SNSMessage {
  private def parseTimestamp(timestamp: String): DateTime =
    ISODateTimeFormat.dateTime.withZoneUTC.parseDateTime(timestamp)

  implicit def snsMessageReads: Reads[SNSMessage] =
    (
      (__ \ "Type").read[String] ~
      (__ \ "MessageId").read[String] ~
      (__ \ "TopicArn").read[String] ~
      (__ \ "Subject").readNullable[String] ~
      (__ \ "Timestamp").read[String].map(parseTimestamp) ~
      (__ \ "Message").read[String].map(Json.parse)
    )(SNSMessage(_, _, _, _, _, _))
}
