/*
 * Copyright 2023 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.apiplatformjobs.connectors

import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.{LocalDate, LocalDateTime}
import scala.concurrent.ExecutionContext.Implicits.global

import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatestplus.play.guice.GuiceOneAppPerSuite

import play.api.Application
import play.api.http.Status._
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.client.HttpClientV2

import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ApplicationName
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{ApplicationId, Environment}

import uk.gov.hmrc.apiplatformjobs.models.{Administrator, UnusedApplication}
import uk.gov.hmrc.apiplatformjobs.utils.{AsyncHmrcSpec, UrlEncoding}

class EmailConnectorSpec extends AsyncHmrcSpec with ResponseUtils with GuiceOneAppPerSuite with WiremockSugar with UrlEncoding {

  override def fakeApplication(): Application =
    GuiceApplicationBuilder()
      .configure("metrics.jvm" -> false)
      .build()

  implicit val hc: HeaderCarrier = HeaderCarrier()

  trait Setup {
    val http      = app.injector.instanceOf[HttpClientV2]
    val config    = EmailConfig(wireMockUrl)
    val connector = new EmailConnector(http, config)
  }

  trait ApplicationToBeDeletedNotificationDetails {
    def daysSince(date: LocalDateTime): Long = ChronoUnit.DAYS.between(LocalDateTime.now().toLocalDate, date.toLocalDate)

    val expectedTemplateId = "apiApplicationToBeDeletedNotification"

    val adminEmail       = "admin1@example.com".toLaxEmail
    val applicationName  = ApplicationName("Test Application")
    val userFirstName    = "Fred"
    val userLastName     = "Bloggs"
    val environmentName  = "Sandbox"
    val timeSinceLastUse = "335 days"

    val lastInteractionDate        = LocalDate.now.minusDays(335)
    val scheduledDeletionDate      = LocalDate.now.plusDays(30)
    val dateTimeFormatter          = DateTimeFormatter.ofPattern("dd MMMM yyyy")
    val expectedDeletionDateString = scheduledDeletionDate.format(dateTimeFormatter)
    val daysToDeletion             = s"${daysSince(scheduledDeletionDate.atStartOfDay()).toString} days"

    val unusedApplication =
      UnusedApplication(
        ApplicationId.random,
        applicationName,
        Seq(Administrator(adminEmail, userFirstName, userLastName)),
        Environment.SANDBOX,
        lastInteractionDate,
        Seq.empty,
        scheduledDeletionDate
      )

    val unusedApplicationWithMultipleAdmins =
      UnusedApplication(
        ApplicationId.random,
        applicationName,
        Seq(Administrator(adminEmail, userFirstName, userLastName), Administrator(adminEmail, userFirstName, userLastName)),
        Environment.SANDBOX,
        lastInteractionDate,
        Seq.empty,
        scheduledDeletionDate
      )
  }

  "emailConnector" should {
    "send unused application to be deleted email" in new Setup with ApplicationToBeDeletedNotificationDetails {
      val expectedToEmails                        = Set(adminEmail)
      val expectedParameters: Map[String, String] = Map(
        "userFirstName"    -> userFirstName,
        "userLastName"     -> userLastName,
        "applicationName"  -> applicationName.value,
        "environmentName"  -> environmentName,
        "timeSinceLastUse" -> timeSinceLastUse,
        "daysToDeletion"   -> daysToDeletion
      )

      stubFor(
        post(urlPathEqualTo("/hmrc/email"))
          .withJsonRequestBody(SendEmailRequest(expectedToEmails, expectedTemplateId, expectedParameters))
          .willReturn(
            aResponse()
              .withStatus(OK)
          )
      )

      val successful = await(connector.sendApplicationToBeDeletedNotifications(unusedApplication, environmentName))

      successful should be(true)
    }

    "return true if any notification succeeds" in new Setup with ApplicationToBeDeletedNotificationDetails {
      stubFor(
        post(urlPathEqualTo("/hmrc/email"))
          .willReturn(
            aResponse()
              .withStatus(OK)
          )
          .willReturn(
            aResponse()
              .withStatus(OK)
          )
      )
      val successful = await(connector.sendApplicationToBeDeletedNotifications(unusedApplicationWithMultipleAdmins, environmentName))

      successful should be(true)
    }

    "return false if all notifications fail" in new Setup with ApplicationToBeDeletedNotificationDetails {
      stubFor(
        post(urlPathEqualTo("/hmrc/email"))
          .willReturn(
            aResponse()
              .withStatus(NOT_FOUND)
          )
      )
      val successful = await(connector.sendApplicationToBeDeletedNotifications(unusedApplicationWithMultipleAdmins, environmentName))

      successful should be(false)
    }
  }
}
