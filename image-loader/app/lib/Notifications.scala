package lib

import com.gu.mediaservice.lib.aws.SNS

object Notifications extends SNS(Config.awsCredentials, Config.topicArn)

object ImageUploadNotifications extends SNS(Config.awsCredentials, Config.imageUploadTopicArn)
