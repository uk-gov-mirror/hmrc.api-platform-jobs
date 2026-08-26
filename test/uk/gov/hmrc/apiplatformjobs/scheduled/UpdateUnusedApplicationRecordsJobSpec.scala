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

package uk.gov.hmrc.apiplatformjobs.scheduled

import java.time.{Instant, LocalDate, ZoneOffset}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.Future.successful

import org.mockito.ArgumentCaptor

import uk.gov.hmrc.apiplatform.modules.applications.core.domain.models.ApplicationNameData
import uk.gov.hmrc.apiplatform.modules.common.domain.models.LaxEmailAddress.StringSyntax
import uk.gov.hmrc.apiplatform.modules.common.domain.models.{ApplicationId, Environment, LaxEmailAddress, UserId}
import uk.gov.hmrc.apiplatform.modules.common.utils.FixedClock

import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyDeveloperConnector.DeveloperResponse
import uk.gov.hmrc.apiplatformjobs.connectors.{ThirdPartyDeveloperConnector, ThirdPartyOrchestratorConnector}
import uk.gov.hmrc.apiplatformjobs.models._
import uk.gov.hmrc.apiplatformjobs.repository.UnusedApplicationsRepository
import uk.gov.hmrc.apiplatformjobs.utils.AsyncHmrcSpec

class UpdateUnusedApplicationRecordsJobSpec extends AsyncHmrcSpec with UnusedApplicationTestConfiguration with FixedClock {

  trait Setup extends BaseSetup {
    val environmentName                                                = "Test Environment"
    val mockTpoConnector: ThirdPartyOrchestratorConnector              = mock[ThirdPartyOrchestratorConnector]
    val mockThirdPartyDeveloperConnector: ThirdPartyDeveloperConnector = mock[ThirdPartyDeveloperConnector]
    val mockUnusedApplicationsRepository: UnusedApplicationsRepository = mock[UnusedApplicationsRepository]
    val nowAsDay                                                       = now.toLocalDate()
  }

  trait SandboxJobSetup extends Setup {
    val deleteUnusedApplicationsAfter    = 365
    val deleteNeverUsedApplicationsAfter = 7
    val notifyDeletionPendingInAdvance   = 30
    val configuration                    = jobConfiguration(deleteUnusedApplicationsAfter, notifyDeletionPendingInAdvanceForSandbox = Seq(notifyDeletionPendingInAdvance))

    val underTest = new UpdateUnusedSandboxApplicationRecordsJob(
      mockTpoConnector,
      mockThirdPartyDeveloperConnector,
      mockUnusedApplicationsRepository,
      configuration,
      FixedClock.clock,
      mockLockRepository
    )
  }

  trait ProductionJobSetup extends Setup {
    val deleteUnusedApplicationsAfter  = 365
    val notifyDeletionPendingInAdvance = 30

    val configuration =
      jobConfiguration(deleteUnusedApplicationsAfter, notifyDeletionPendingInAdvanceForProduction = Seq(notifyDeletionPendingInAdvance))

    val underTest = new UpdateUnusedProductionApplicationRecordsJob(
      mockTpoConnector,
      mockThirdPartyDeveloperConnector,
      mockUnusedApplicationsRepository,
      configuration,
      FixedClock.clock,
      mockLockRepository
    )
  }

  trait MultipleNotificationsSetup extends Setup {
    val deleteUnusedApplicationsAfter  = 365
    val notifyDeletionPendingInAdvance = Seq(30, 14, 7)
    val configuration                  = jobConfiguration(deleteUnusedApplicationsAfter, notifyDeletionPendingInAdvanceForProduction = notifyDeletionPendingInAdvance)

    val underTest = new UpdateUnusedProductionApplicationRecordsJob(
      mockTpoConnector,
      mockThirdPartyDeveloperConnector,
      mockUnusedApplicationsRepository,
      configuration,
      FixedClock.clock,
      mockLockRepository
    )
  }

  "SANDBOX job" should {
    "add newly discovered never used application" in new SandboxJobSetup {
      val adminUserEmail            = "foo@bar.com".toLaxEmail
      val date31DaysAgo             = now.minusDays(31).toInstant(ZoneOffset.UTC)
      val (neverUsedApplication, _) =
        applicationDetails(Environment.SANDBOX, date31DaysAgo, None, Set(adminUserEmail))

      when(mockTpoConnector.findApplicationsThatHaveNotBeenUsedSince(eqTo(Environment.SANDBOX), *)).thenReturn(successful(List.empty))
      when(mockTpoConnector.findApplicationsThatHaveNeverBeenUsedCreatedBefore(eqTo(Environment.SANDBOX), *)).thenReturn(successful(List(neverUsedApplication)))
      when(mockThirdPartyDeveloperConnector.fetchVerifiedDevelopers(Set(adminUserEmail)))
        .thenReturn(successful(Seq(DeveloperResponse(adminUserEmail, "Foo", "Bar", true, UserId.random))))
      when(mockUnusedApplicationsRepository.unusedApplications(Environment.SANDBOX)).thenReturn(Future(List.empty))

      val insertCaptor: ArgumentCaptor[Seq[UnusedApplication]] = ArgumentCaptor.forClass(classOf[Seq[UnusedApplication]])
      when(mockUnusedApplicationsRepository.bulkInsert(*)).thenReturn(Future.successful(1))
      await(underTest.runJob)

      verify(mockUnusedApplicationsRepository).bulkInsert(insertCaptor.capture())

      val capturedInsertValue     = insertCaptor.getValue
      capturedInsertValue.size shouldBe (1)
      val unusedApplicationRecord = capturedInsertValue.head
      unusedApplicationRecord.applicationId shouldBe (neverUsedApplication.applicationId)
      unusedApplicationRecord.applicationName shouldBe (neverUsedApplication.applicationName)
      unusedApplicationRecord.environment shouldBe (Environment.SANDBOX)
      val expectedDeletionDate    = now.plusDays(7).toLocalDate()
      unusedApplicationRecord.scheduledDeletionDate shouldBe expectedDeletionDate
      unusedApplicationRecord.scheduledNotificationDates shouldBe List(expectedDeletionDate.minusDays(7), expectedDeletionDate.minusDays(1))
    }

    "add newly discovered unused applications with last used dates to database" in new SandboxJobSetup {
      val adminUserEmail                                                           = "foo@bar.com".toLaxEmail
      val applicationWithLastUseDate: (ApplicationUsageDetails, UnusedApplication) =
        applicationDetails(Environment.SANDBOX, now.minusMonths(14).toInstant(ZoneOffset.UTC), Some(now.minusMonths(13).toInstant(ZoneOffset.UTC)), Set(adminUserEmail))

      when(mockTpoConnector.findApplicationsThatHaveNotBeenUsedSince(eqTo(Environment.SANDBOX), *)).thenReturn(successful(List(applicationWithLastUseDate._1)))
      when(mockTpoConnector.findApplicationsThatHaveNeverBeenUsedCreatedBefore(eqTo(Environment.SANDBOX), *)).thenReturn(successful(List.empty))
      when(mockThirdPartyDeveloperConnector.fetchVerifiedDevelopers(Set(adminUserEmail)))
        .thenReturn(successful(Seq(DeveloperResponse(adminUserEmail, "Foo", "Bar", true, UserId.random))))
      when(mockUnusedApplicationsRepository.unusedApplications(Environment.SANDBOX)).thenReturn(Future(List.empty))

      val insertCaptor: ArgumentCaptor[Seq[UnusedApplication]] = ArgumentCaptor.forClass(classOf[Seq[UnusedApplication]])
      when(mockUnusedApplicationsRepository.bulkInsert(*)).thenReturn(Future.successful(1))
      await(underTest.runJob)

      verify(mockUnusedApplicationsRepository).bulkInsert(insertCaptor.capture())

      val capturedInsertValue     = insertCaptor.getValue
      capturedInsertValue.size shouldBe (1)
      val unusedApplicationRecord = capturedInsertValue.head
      unusedApplicationRecord.applicationId shouldBe (applicationWithLastUseDate._1.applicationId)
      unusedApplicationRecord.applicationName shouldBe (applicationWithLastUseDate._1.applicationName)
      unusedApplicationRecord.environment shouldBe (Environment.SANDBOX)
      val expectedDeletionDate    = now.plusDays(30).toLocalDate()
      unusedApplicationRecord.scheduledDeletionDate shouldBe expectedDeletionDate
      unusedApplicationRecord.scheduledNotificationDates shouldBe List(expectedDeletionDate.minusDays(30))
    }

    // 2026-03-12: TPA.applications.lastAccess is now always populated, but it is still an Option in the model, so keeping this test for now
    "add newly discovered unused applications with no last used dates to database" in new SandboxJobSetup {
      val adminUserEmail                                                              = "foo@bar.com".toLaxEmail
      val applicationWithoutLastUseDate: (ApplicationUsageDetails, UnusedApplication) =
        applicationDetails(Environment.SANDBOX, now.minusMonths(13).toInstant(ZoneOffset.UTC), None, Set(adminUserEmail)) // scalastyle:off magic.number

      when(mockTpoConnector.findApplicationsThatHaveNotBeenUsedSince(eqTo(Environment.SANDBOX), *)).thenReturn(successful(List(applicationWithoutLastUseDate._1)))
      when(mockTpoConnector.findApplicationsThatHaveNeverBeenUsedCreatedBefore(eqTo(Environment.SANDBOX), *)).thenReturn(successful(List.empty))
      when(mockThirdPartyDeveloperConnector.fetchVerifiedDevelopers(Set(adminUserEmail)))
        .thenReturn(successful(Seq(DeveloperResponse(adminUserEmail, "Foo", "Bar", true, UserId.random))))
      when(mockUnusedApplicationsRepository.unusedApplications(Environment.SANDBOX)).thenReturn(Future(List.empty))

      val insertCaptor: ArgumentCaptor[Seq[UnusedApplication]] = ArgumentCaptor.forClass(classOf[Seq[UnusedApplication]])
      when(mockUnusedApplicationsRepository.bulkInsert(*)).thenReturn(Future.successful(1))
      await(underTest.runJob)

      verify(mockUnusedApplicationsRepository).bulkInsert(insertCaptor.capture())
      val capturedInsertValue     = insertCaptor.getValue
      capturedInsertValue.size shouldBe (1)
      val unusedApplicationRecord = capturedInsertValue.head
      unusedApplicationRecord.applicationId shouldBe (applicationWithoutLastUseDate._1.applicationId)
      unusedApplicationRecord.applicationName shouldBe (applicationWithoutLastUseDate._1.applicationName)
      unusedApplicationRecord.environment shouldBe (Environment.SANDBOX)
      val expectedDeletionDate    = now.plusDays(7).toLocalDate()
      unusedApplicationRecord.scheduledDeletionDate shouldBe expectedDeletionDate
      unusedApplicationRecord.scheduledNotificationDates shouldBe List(expectedDeletionDate.minusDays(7), expectedDeletionDate.minusDays(1))
    }

    "not persist application details already stored in database" in new SandboxJobSetup {
      val application: (ApplicationUsageDetails, UnusedApplication) =
        applicationDetails(
          Environment.SANDBOX,
          now.minusMonths(13).toInstant(ZoneOffset.UTC),
          Some(now.minusMonths(13).toInstant(ZoneOffset.UTC)),
          Set()
        ) // scalastyle:off magic.number

      when(mockTpoConnector.findApplicationsThatHaveNotBeenUsedSince(eqTo(Environment.SANDBOX), *)).thenReturn(successful(List(application._1)))
      when(mockTpoConnector.findApplicationsThatHaveNeverBeenUsedCreatedBefore(eqTo(Environment.SANDBOX), *)).thenReturn(successful(List.empty))

      when(mockUnusedApplicationsRepository.unusedApplications(Environment.SANDBOX)).thenReturn(Future(List(application._2)))

      await(underTest.runJob)

      verifyZeroInteractions(mockThirdPartyDeveloperConnector)
    }

    "remove applications that have been updated since last run" in new SandboxJobSetup {
      val application: (ApplicationUsageDetails, UnusedApplication) =
        applicationDetails(
          Environment.SANDBOX,
          now.minusMonths(13).toInstant(ZoneOffset.UTC),
          Some(now.minusMonths(13).toInstant(ZoneOffset.UTC)),
          Set()
        ) // scalastyle:off magic.number

      when(mockTpoConnector.findApplicationsThatHaveNotBeenUsedSince(eqTo(Environment.SANDBOX), *)).thenReturn(successful(List.empty))
      when(mockTpoConnector.findApplicationsThatHaveNeverBeenUsedCreatedBefore(eqTo(Environment.SANDBOX), *)).thenReturn(successful(List.empty))
      when(mockUnusedApplicationsRepository.unusedApplications(Environment.SANDBOX)).thenReturn(Future(List(application._2)))
      when(mockUnusedApplicationsRepository.deleteUnusedApplicationRecord(eqTo(Environment.SANDBOX), *[ApplicationId])).thenReturn(successful(true))

      await(underTest.runJob)

      verify(mockUnusedApplicationsRepository).deleteUnusedApplicationRecord(Environment.SANDBOX, application._2.applicationId)

      verifyZeroInteractions(mockThirdPartyDeveloperConnector)
    }

  }

  "PRODUCTION job" should {
    "add newly discovered unused applications with last used dates to database" in new ProductionJobSetup {
      val adminUserEmail                                                           = "foo@bar.com".toLaxEmail
      val applicationWithLastUseDate: (ApplicationUsageDetails, UnusedApplication) =
        applicationDetails(
          Environment.PRODUCTION,
          now.minusMonths(13).toInstant(ZoneOffset.UTC),
          Some(now.minusMonths(13).toInstant(ZoneOffset.UTC)),
          Set(adminUserEmail)
        ) // scalastyle:off magic.number

      when(mockTpoConnector.findApplicationsThatHaveNotBeenUsedSince(eqTo(Environment.PRODUCTION), *))
        .thenReturn(successful(List(applicationWithLastUseDate._1)))
      when(mockThirdPartyDeveloperConnector.fetchVerifiedDevelopers(Set(adminUserEmail)))
        .thenReturn(successful(Seq(DeveloperResponse(adminUserEmail, "Foo", "Bar", true, UserId.random))))
      when(mockUnusedApplicationsRepository.unusedApplications(Environment.PRODUCTION)).thenReturn(Future(List.empty))

      val insertCaptor: ArgumentCaptor[Seq[UnusedApplication]] = ArgumentCaptor.forClass(classOf[Seq[UnusedApplication]])
      when(mockUnusedApplicationsRepository.bulkInsert(*)).thenReturn(Future.successful(1))
      await(underTest.runJob)

      verify(mockUnusedApplicationsRepository).bulkInsert(insertCaptor.capture())
      val capturedInsertValue     = insertCaptor.getValue
      capturedInsertValue.size shouldBe (1)
      val unusedApplicationRecord = capturedInsertValue.head
      unusedApplicationRecord.applicationId shouldBe (applicationWithLastUseDate._1.applicationId)
      unusedApplicationRecord.applicationName shouldBe (applicationWithLastUseDate._1.applicationName)
      unusedApplicationRecord.environment shouldBe (Environment.PRODUCTION)
    }

    "add newly discovered unused applications with no last used dates to database" in new ProductionJobSetup {
      val adminUserEmail                                                              = "foo@bar.com".toLaxEmail
      val applicationWithoutLastUseDate: (ApplicationUsageDetails, UnusedApplication) =
        applicationDetails(Environment.PRODUCTION, now.minusMonths(13).toInstant(ZoneOffset.UTC), None, Set(adminUserEmail)) // scalastyle:off magic.number

      when(mockTpoConnector.findApplicationsThatHaveNotBeenUsedSince(eqTo(Environment.PRODUCTION), *)).thenReturn(successful(List(applicationWithoutLastUseDate._1)))
      when(mockThirdPartyDeveloperConnector.fetchVerifiedDevelopers(Set(adminUserEmail)))
        .thenReturn(successful(Seq(DeveloperResponse(adminUserEmail, "Foo", "Bar", true, UserId.random))))
      when(mockUnusedApplicationsRepository.unusedApplications(Environment.PRODUCTION)).thenReturn(Future(List.empty))

      val insertCaptor: ArgumentCaptor[Seq[UnusedApplication]] = ArgumentCaptor.forClass(classOf[Seq[UnusedApplication]])
      when(mockUnusedApplicationsRepository.bulkInsert(*)).thenReturn(Future.successful(1))
      await(underTest.runJob)

      verify(mockUnusedApplicationsRepository).bulkInsert(insertCaptor.capture())
      val capturedInsertValue     = insertCaptor.getValue
      capturedInsertValue.size shouldBe (1)
      val unusedApplicationRecord = capturedInsertValue.head
      unusedApplicationRecord.applicationId shouldBe (applicationWithoutLastUseDate._1.applicationId)
      unusedApplicationRecord.applicationName shouldBe (applicationWithoutLastUseDate._1.applicationName)
      unusedApplicationRecord.environment shouldBe (Environment.PRODUCTION)
    }

    "not persist application details already stored in database" in new ProductionJobSetup {
      val application: (ApplicationUsageDetails, UnusedApplication) =
        applicationDetails(
          Environment.PRODUCTION,
          now.minusMonths(13).toInstant(ZoneOffset.UTC),
          Some(now.minusMonths(13).toInstant(ZoneOffset.UTC)),
          Set()
        ) // scalastyle:off magic.number

      when(mockTpoConnector.findApplicationsThatHaveNotBeenUsedSince(eqTo(Environment.PRODUCTION), *))
        .thenReturn(successful(List(application._1)))
      when(mockUnusedApplicationsRepository.unusedApplications(Environment.PRODUCTION)).thenReturn(Future(List(application._2)))

      await(underTest.runJob)

      // verify(mockUnusedApplicationsRepository, times(0)).collection.bulkWrite(*)

      verifyZeroInteractions(mockThirdPartyDeveloperConnector)
    }

    "remove applications that have been updated since last run" in new ProductionJobSetup {

      val application: (ApplicationUsageDetails, UnusedApplication) =
        applicationDetails(
          Environment.PRODUCTION,
          now.minusMonths(13).toInstant(ZoneOffset.UTC),
          Some(now.minusMonths(13).toInstant(ZoneOffset.UTC)),
          Set()
        ) // scalastyle:off magic.number

      when(mockTpoConnector.findApplicationsThatHaveNotBeenUsedSince(eqTo(Environment.PRODUCTION), *)).thenReturn(successful(List.empty))
      when(mockUnusedApplicationsRepository.unusedApplications(eqTo(Environment.PRODUCTION))).thenReturn(Future(List(application._2)))
      when(mockUnusedApplicationsRepository.deleteUnusedApplicationRecord(eqTo(Environment.PRODUCTION), *[ApplicationId])).thenReturn(successful(true))

      await(underTest.runJob)

      verify(mockUnusedApplicationsRepository).deleteUnusedApplicationRecord(eqTo(Environment.PRODUCTION), eqTo(application._2.applicationId))

      // verify(mockUnusedApplicationsRepository, times(0)).collection.bulkWrite(*)

      verifyZeroInteractions(mockThirdPartyDeveloperConnector)
    }
  }

  private def applicationDetails(
      environment: Environment,
      creationDate: Instant,
      lastAccessDate: Option[Instant],
      administrators: Set[LaxEmailAddress]
    ): (ApplicationUsageDetails, UnusedApplication) = {
    val applicationId        = ApplicationId.random
    val applicationName      = ApplicationNameData.one
    val administratorDetails = administrators.map(admin => new Administrator(admin, "Foo", "Bar"))
    val lastInteractionDate  = LocalDate.ofInstant(lastAccessDate.getOrElse(creationDate), ZoneOffset.UTC)

    (
      ApplicationUsageDetails(applicationId, applicationName, administrators, creationDate, lastAccessDate),
      UnusedApplication(
        applicationId,
        applicationName,
        administratorDetails.toSeq,
        environment,
        lastInteractionDate,
        scheduledNotificationDates = List(now.toLocalDate()), // who cares
        scheduledDeletionDate = now.toLocalDate()             // who cares
      )
    )
  }
}
