package lib.elasticsearch

import scalaz.NonEmptyList
import scalaz.syntax.foldable1._

import org.joda.time.DateTime
import org.elasticsearch.index.query.{BoolFilterBuilder, FilterBuilders, FilterBuilder}

import com.gu.mediaservice.lib.formatting.printDateTime


object filters {

  import FilterBuilders.{
    rangeFilter,
    termsFilter,
    boolFilter,
    notFilter,
    existsFilter,
    missingFilter,
    termFilter
  }


  def date(from: Option[DateTime], to: Option[DateTime]): FilterBuilder = {
    val builder = rangeFilter("uploadTime")
    for (f <- from) builder.from(printDateTime(f))
    for (t <- to) builder.to(printDateTime(t))
    builder
  }

  def term(field: String, term: String): FilterBuilder =
    termFilter(field, term)

  def terms(field: String, terms: NonEmptyList[String]): FilterBuilder =
    termsFilter(field, terms.list: _*)

  // Note: slightly leaky API, would be nice to keep our DSL abstracted
  def bool: BoolFilterBuilder = boolFilter()

  def and(filters: FilterBuilder*): FilterBuilder =
    boolFilter().must(filters: _*)

  def or(filters: FilterBuilder*): FilterBuilder =
    boolFilter().should(filters: _*)

  def or(filters: NonEmptyList[FilterBuilder]): FilterBuilder = or(filters.list: _*)

  def not(filter: FilterBuilder): FilterBuilder =
    notFilter(filter)

  def exists(fields: NonEmptyList[String]): FilterBuilder =
    fields.map(f => existsFilter(f): FilterBuilder).foldRight1(and(_, _))

  def missing(fields: NonEmptyList[String]): FilterBuilder =
    fields.map(f => missingFilter(f): FilterBuilder).foldRight1(and(_, _))

  def anyMissing(fields: NonEmptyList[String]): FilterBuilder =
    fields.map(f => missingFilter(f): FilterBuilder).foldRight1(or(_, _))

  def ids(idList: List[String]): FilterBuilder =
    FilterBuilders.idsFilter().addIds(idList:_*)

  def existsOrMissing(field: String, exists: Boolean): FilterBuilder = exists match {
    case true  => filters.exists(NonEmptyList(field))
    case false => filters.missing(NonEmptyList(field))
  }

}
