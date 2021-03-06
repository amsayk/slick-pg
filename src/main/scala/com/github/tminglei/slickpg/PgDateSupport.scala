package com.github.tminglei.slickpg

import scala.slick.driver.PostgresDriver
import java.sql.{Timestamp, Time, Date}
import javax.xml.bind.DatatypeConverter
import scala.slick.lifted.Column
import java.util.Calendar

trait PgDateSupport extends date.PgDateExtensions with date.PgDateJdbcTypes with utils.PgCommonJdbcTypes { driver: PostgresDriver =>
  import driver.Implicit._

  type DATE   = Date
  type TIME   = Time
  type TIMESTAMP = Timestamp
  type INTERVAL  = Interval
  
  type TIMESTAMP_TZ = Calendar

  trait DateTimeImplicits {
    implicit val intervalTypeMapper = new GenericJdbcType[Interval]("interval", Interval.apply, hasLiteralForm=false)
    implicit val timestampTZTypeMapper = new GenericJdbcType[Calendar]("timestamptz",
        PgDateSupportUtils.parseCalendar, DatatypeConverter.printDateTime, hasLiteralForm=false)

    ///
    implicit def dateColumnExtensionMethods(c: Column[Date]) = new DateColumnExtensionMethods(c)
    implicit def dateOptColumnExtensionMethods(c: Column[Option[Date]]) = new DateColumnExtensionMethods(c)

    implicit def timeColumnExtensionMethods(c: Column[Time]) = new TimeColumnExtensionMethods(c)
    implicit def timeOptColumnExtensionMethods(c: Column[Option[Time]]) = new TimeColumnExtensionMethods(c)

    implicit def timestampColumnExtensionMethods(c: Column[Timestamp]) = new TimestampColumnExtensionMethods(c)
    implicit def timestampOptColumnExtensionMethods(c: Column[Option[Timestamp]]) = new TimestampColumnExtensionMethods(c)

    implicit def intervalColumnExtensionMethods(c: Column[Interval]) = new IntervalColumnExtensionMethods(c)
    implicit def intervalOptColumnExtensionMethods(c: Column[Option[Interval]]) = new IntervalColumnExtensionMethods(c)

    implicit def timestampTZColumnExtensionMethods(c: Column[Calendar]) = new TimestampTZColumnExtensionMethods(c)
    implicit def timestampTZOptColumnExtensionMethods(c: Column[Option[Calendar]]) = new TimestampTZColumnExtensionMethods(c)
  }
}

object PgDateSupportUtils {
  import org.postgresql.jdbc2.TimestampUtils
  import java.lang.reflect.{Field, Method}

  /** related codes hacked from [[org.postgresql.jdbc2.TimestampUtils]] */
  def parseCalendar(tsStr: String): Calendar = {

    val ts = tsUtilLoadCalendar.invoke(tsUtilInstance, null, tsStr, "timestamp")

    val (tz, era, year, month, day, hour, minute, second, nanos) = tsUtilGetters(ts)
    val useCal: Calendar = if (tz.get(ts) == null) Calendar.getInstance() else tz.get(ts).asInstanceOf[Calendar]

    useCal.set(Calendar.ERA, era.get(ts).asInstanceOf[Int])
    useCal.set(Calendar.YEAR, year.get(ts).asInstanceOf[Int])
    useCal.set(Calendar.MONTH, month.get(ts).asInstanceOf[Int] - 1)
    useCal.set(Calendar.DAY_OF_MONTH, day.get(ts).asInstanceOf[Int])
    useCal.set(Calendar.HOUR_OF_DAY, hour.get(ts).asInstanceOf[Int])
    useCal.set(Calendar.MINUTE, minute.get(ts).asInstanceOf[Int])
    useCal.set(Calendar.SECOND, second.get(ts).asInstanceOf[Int])
    useCal.set(Calendar.MILLISECOND, nanos.get(ts).asInstanceOf[Int] / 1000)

    useCal
  }

  //////////////////////////////////////////////////////////////////////
  private val tsUtilInstanceHolder = new ThreadLocal[TimestampUtils]
  private val tsUtilLoadCalendarHolder = new ThreadLocal[Method]
  private val tsUtilParsedGettersHolder = new ThreadLocal[(Field, Field, Field, Field, Field, Field, Field, Field, Field)]

  private def tsUtilInstance = {
    import java.lang.Boolean.TRUE
    if (tsUtilInstanceHolder.get() == null) {
      val tsUtilConstructor = classOf[TimestampUtils].getDeclaredConstructor(classOf[Boolean], classOf[Boolean], classOf[Boolean])
      tsUtilConstructor.setAccessible(true)
      tsUtilInstanceHolder.set(tsUtilConstructor.newInstance(TRUE, TRUE, TRUE))
    }
    tsUtilInstanceHolder.get()
  }

  private def tsUtilLoadCalendar = {
    if (tsUtilLoadCalendarHolder.get() == null) {
      val loadCalendar = classOf[TimestampUtils].getDeclaredMethods.find(_.getName == "loadCalendar").get
      loadCalendar.setAccessible(true)
      tsUtilLoadCalendarHolder.set(loadCalendar)
    }
    tsUtilLoadCalendarHolder.get()
  }

  private def tsUtilGetters(parsed: AnyRef) = {
    def getField(clazz: Class[_], name: String) = {
      val field = clazz.getDeclaredField(name)
      field.setAccessible(true)
      field
    }

    if (tsUtilParsedGettersHolder.get() == null) {
      val clazz = parsed.getClass
      val tz = getField(clazz, "tz")
      val era = getField(clazz, "era")
      val year = getField(clazz, "year")
      val month = getField(clazz, "month")
      val day = getField(clazz, "day")
      val hour = getField(clazz, "hour")
      val minute = getField(clazz, "minute")
      val second = getField(clazz, "second")
      val nanos = getField(clazz, "nanos")

      tsUtilParsedGettersHolder.set((tz, era, year, month, day, hour, minute, second, nanos))
    }
    tsUtilParsedGettersHolder.get()
  }
}

/**
 * copy from [[org.postgresql.util.PGInterval]],
 * should be more convenient to be used in scala environment
 */
import java.text.DecimalFormat
import org.postgresql.util.PGInterval

case class Interval(
  years: Int,
  months: Int,
  days: Int,
  hours: Int,
  minutes: Int,
  seconds: Double) {

  def milliseconds: Int = (microseconds + (if (microseconds < 0) -500 else 500)) / 1000
  def microseconds: Int = (seconds * 1000000.0).asInstanceOf[Int]

  def +:(cal: Calendar): Calendar = {
    cal.add(Calendar.MILLISECOND, milliseconds)
    cal.add(Calendar.MINUTE, minutes)
    cal.add(Calendar.HOUR, hours)
    cal.add(Calendar.DAY_OF_MONTH, days)
    cal.add(Calendar.MONTH, months)
    cal.add(Calendar.YEAR, years)
    cal
  }

  def +:(date: java.util.Date): java.util.Date = {
    val cal = Calendar.getInstance
    cal.setTime(date)
    date.setTime((cal +: this).getTime.getTime)
    date
  }

  def +(other: Interval): Interval = {
    new Interval(
      years + other.years,
      months + other.months,
      days + other.days,
      hours + other.hours,
      minutes + other.minutes,
      seconds + other.seconds
    )
  }

  def *(factor: Int): Interval = {
    new Interval(
      years * factor,
      months * factor,
      days * factor,
      hours * factor,
      minutes * factor,
      seconds * factor
    )
  }

  override def toString = {
    val secs = Interval.secondsFormat.format(seconds)
    s"$years years $months mons $days days $hours hours $minutes mins $secs secs"
  }
}

object Interval {
  private val secondsFormat = new DecimalFormat("0.00####")

  def apply(interval: String): Interval = fromPgInterval(new PGInterval(interval))

  def fromPgInterval(interval: PGInterval): Interval = {
    new Interval(
      interval.getYears,
      interval.getMonths,
      interval.getDays,
      interval.getHours,
      interval.getMinutes,
      interval.getSeconds
    )
  }

  def toPgInterval(interval: Interval): PGInterval = {
    new PGInterval(
      interval.years,
      interval.months,
      interval.days,
      interval.hours,
      interval.minutes,
      interval.seconds
    )
  }
}