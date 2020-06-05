/*
 * Copyright 2020 HM Revenue & Customs
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

package uk.gov.hmrc.apiplatformjobs.scheduled

import java.util.UUID

import org.joda.time.{DateTime, DateTimeUtils}
import org.mockito.Mockito.{times, verify, when}
import org.mockito.ArgumentMatchersSugar
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import play.api.test.{DefaultAwaitTimeout, FutureAwaits}
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.apiplatformjobs.connectors.EmailConnector
import uk.gov.hmrc.apiplatformjobs.models.Environment.{Environment, PRODUCTION, SANDBOX}
import uk.gov.hmrc.apiplatformjobs.models.UnusedApplication
import uk.gov.hmrc.apiplatformjobs.repository.UnusedApplicationsRepository
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Future.successful

class SendUnusedApplicationNotificationsJobSpec extends PlaySpec
  with UnusedApplicationTestConfiguration with MockitoSugar with ArgumentMatchersSugar with MongoSpecSupport with FutureAwaits with DefaultAwaitTimeout {

  val FixedTime = DateTime.now
  DateTimeUtils.setCurrentMillisFixed(FixedTime.getMillis)

  trait Setup {
    val reactiveMongoComponent: ReactiveMongoComponent = new ReactiveMongoComponent {
      override def mongoConnector: MongoConnector = mongoConnectorForTest
    }

    val mockEmailConnector: EmailConnector = mock[EmailConnector]
    val mockUnusedApplicationsRepository: UnusedApplicationsRepository = mock[UnusedApplicationsRepository]
  }

  trait SandboxSetup extends Setup {
    val environment: Environment = SANDBOX

    val underTest =
      new SendUnusedSandboxApplicationNotificationsJob(
        mockUnusedApplicationsRepository,
        mockEmailConnector,
        defaultConfiguration,
        reactiveMongoComponent)
  }

  trait ProductionSetup extends Setup {
    val environment: Environment = PRODUCTION

    val underTest =
      new SendUnusedProductionApplicationNotificationsJob(
        mockUnusedApplicationsRepository,
        mockEmailConnector,
        defaultConfiguration,
        reactiveMongoComponent)
  }

  "SANDBOX job" should {
    "send notifications for applications that are due" in new SandboxSetup {
      val unusedApplication: UnusedApplication = unusedApplicationRecord(UUID.randomUUID, environment)

      when(mockUnusedApplicationsRepository.unusedApplicationsToBeNotified(environment, FixedTime)).thenReturn(Future.successful(List(unusedApplication)))
      when(mockEmailConnector.sendApplicationToBeDeletedNotifications(unusedApplication)).thenReturn(successful(true))
      when(mockUnusedApplicationsRepository.updateNotificationsSent(environment, unusedApplication.applicationId, FixedTime)).thenReturn(successful(true))

      await(underTest.runJob)

      verify(mockEmailConnector).sendApplicationToBeDeletedNotifications(unusedApplication)
      verify(mockUnusedApplicationsRepository, times(1)).updateNotificationsSent(environment, unusedApplication.applicationId, FixedTime)
    }

    "not remove scheduled notification date if notifications could not be sent" in new SandboxSetup {
      val unusedApplication: UnusedApplication = unusedApplicationRecord(UUID.randomUUID, environment)

      when(mockUnusedApplicationsRepository.unusedApplicationsToBeNotified(environment, FixedTime)).thenReturn(Future.successful(List(unusedApplication)))
      when(mockEmailConnector.sendApplicationToBeDeletedNotifications(unusedApplication)).thenReturn(successful(false))

      await(underTest.runJob)

      verify(mockEmailConnector).sendApplicationToBeDeletedNotifications(unusedApplication)
      verify(mockUnusedApplicationsRepository, times(0)).updateNotificationsSent(*, *, *)
    }
  }

  "PRODUCTION job" should {
    "send notifications for applications that are due" in new ProductionSetup {
      val unusedApplication: UnusedApplication = unusedApplicationRecord(UUID.randomUUID, environment)

      when(mockUnusedApplicationsRepository.unusedApplicationsToBeNotified(environment, FixedTime)).thenReturn(Future.successful(List(unusedApplication)))
      when(mockEmailConnector.sendApplicationToBeDeletedNotifications(unusedApplication)).thenReturn(successful(true))
      when(mockUnusedApplicationsRepository.updateNotificationsSent(environment, unusedApplication.applicationId, FixedTime)).thenReturn(successful(true))

      await(underTest.runJob)

      verify(mockEmailConnector).sendApplicationToBeDeletedNotifications(unusedApplication)
      verify(mockUnusedApplicationsRepository, times(1)).updateNotificationsSent(environment, unusedApplication.applicationId, FixedTime)
    }

    "not remove scheduled notification date if notifications could not be sent" in new ProductionSetup {
      val unusedApplication: UnusedApplication = unusedApplicationRecord(UUID.randomUUID, environment)

      when(mockUnusedApplicationsRepository.unusedApplicationsToBeNotified(environment, FixedTime)).thenReturn(Future.successful(List(unusedApplication)))
      when(mockEmailConnector.sendApplicationToBeDeletedNotifications(unusedApplication)).thenReturn(successful(false))

      await(underTest.runJob)

      verify(mockEmailConnector).sendApplicationToBeDeletedNotifications(unusedApplication)
      verify(mockUnusedApplicationsRepository, times(0)).updateNotificationsSent(*, *, *)
    }
  }
}
