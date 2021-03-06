package kamon

import java.time.Duration

import com.typesafe.config.Config
import _root_.kamino.IngestionV1.Plan
import org.slf4j.LoggerFactory
import java.net.Proxy
import java.util.regex.Pattern

package object apm {
  private[apm] val _logger = LoggerFactory.getLogger("kamon.apm")
  private val _apiKeyPattern = Pattern.compile("^[a-zA-Z0-9]*$")

  def readSettings(config: Config, path: String): Settings = {
    val apmConfig = config.getConfig(path)
    val apiKey = apmConfig.getString("api-key")

    if(apiKey.equals("none"))
      _logger.error("No API key defined in the kamon.apm.api-key setting")

    Settings (
      apiKey            = apiKey,
      plan              = if(apmConfig.getBoolean("enable-tracing")) Plan.METRIC_TRACING else Plan.METRIC_ONLY,
      connectionTimeout = apmConfig.getDuration("client.timeouts.connection"),
      readTimeout       = apmConfig.getDuration("client.timeouts.read"),
      appVersion        = apmConfig.getString("app-version"),
      ingestionApi      = apmConfig.getString("ingestion-api"),
      bootRetries       = apmConfig.getInt("retries.boot"),
      ingestionRetries  = apmConfig.getInt("retries.ingestion"),
      shutdownRetries   = apmConfig.getInt("retries.shutdown"),
      tracingRetries    = apmConfig.getInt("retries.tracing"),
      retryBackoff      = apmConfig.getDuration("retries.backoff"),
      clientBackoff     = apmConfig.getDuration("client.backoff"),
      proxyHost         = apmConfig.getString("proxy.host"),
      proxyPort         = apmConfig.getInt("proxy.port"),
      proxy             = apmConfig.getString("proxy.type").toLowerCase match {
        case "system" => None
        case "socks"  => Some(Proxy.Type.SOCKS)
        case "https"  => Some(Proxy.Type.HTTP)
      }
    )
  }

  def isAcceptableApiKey(apiKey: String): Boolean =
    apiKey != null && apiKey.length == 26 && _apiKeyPattern.matcher(apiKey).matches()

  case class Settings (
    apiKey: String,
    plan: Plan,
    connectionTimeout: Duration,
    readTimeout: Duration,
    appVersion: String,
    ingestionApi: String,
    bootRetries: Int,
    ingestionRetries: Int,
    shutdownRetries: Int,
    tracingRetries: Int,
    retryBackoff: Duration,
    clientBackoff: Duration,
    proxy: Option[Proxy.Type],
    proxyHost: String,
    proxyPort: Int
  ) {
    def ingestionRoute  = s"$ingestionApi/ingest"
    def bootMark        = s"$ingestionApi/hello"
    def shutdownMark    = s"$ingestionApi/goodbye"
    def tracingRoute    = s"$ingestionApi/tracing/ingest"
  }

  /*
   *  Internal HDR Histogram state required to convert index to values and get bucket size information. These values
   *  correspond to a histogram configured to have 2 significant value digits prevision and a smallest discernible value
   *  of 1.
   */
  def countsArrayIndex(value: Long): Int = {

    val SubBucketHalfCountMagnitude = 7
    val SubBucketHalfCount          = 128
    val UnitMagnitude               = 0
    val SubBucketCount              = Math.pow(2, SubBucketHalfCountMagnitude + 1).toInt
    val LeadingZeroCountBase        = 64 - UnitMagnitude - SubBucketHalfCountMagnitude - 1
    val SubBucketMask               = (SubBucketCount.toLong - 1) << UnitMagnitude

    def countsArrayIndex(bucketIndex: Int, subBucketIndex: Int): Int = {
      val bucketBaseIndex = (bucketIndex + 1) << SubBucketHalfCountMagnitude
      val offsetInBucket = subBucketIndex - SubBucketHalfCount
      bucketBaseIndex + offsetInBucket
    }

    def getBucketIndex(value: Long): Int =
      LeadingZeroCountBase - java.lang.Long.numberOfLeadingZeros(value | SubBucketMask)

    def getSubBucketIndex(value: Long, bucketIndex: Long): Int  =
      Math.floor(value / Math.pow(2, (bucketIndex + UnitMagnitude))).toInt

    if (value < 0) throw new ArrayIndexOutOfBoundsException("Histogram recorded value cannot be negative.")
    val bucketIndex = getBucketIndex(value)
    val subBucketIndex = getSubBucketIndex(value, bucketIndex)
    countsArrayIndex(bucketIndex, subBucketIndex)
  }


}
