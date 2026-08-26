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

import java.time.temporal.ChronoUnit
import java.time.{Clock, Instant, LocalDate}
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

import play.api.Configuration
import uk.gov.hmrc.mongo.lock.LockRepository

import uk.gov.hmrc.apiplatform.modules.common.domain.models.{ApplicationId, Environment, LaxEmailAddress}
import uk.gov.hmrc.apiplatform.modules.common.services.DateTimeHelper.InstantConversionSyntax

import uk.gov.hmrc.apiplatformjobs.connectors.{ThirdPartyDeveloperConnector, ThirdPartyOrchestratorConnector}
import uk.gov.hmrc.apiplatformjobs.models._
import uk.gov.hmrc.apiplatformjobs.repository.UnusedApplicationsRepository

abstract class UpdateUnusedApplicationRecordsJob(
    tpoConnector: ThirdPartyOrchestratorConnector,
    thirdPartyDeveloperConnector: ThirdPartyDeveloperConnector,
    unusedApplicationsRepository: UnusedApplicationsRepository,
    environment: Environment,
    configuration: Configuration,
    override val clock: Clock,
    lockRepository: LockRepository
  ) extends UnusedApplicationsJob("UpdateUnusedApplicationRecordsJob", environment, configuration, clock, lockRepository) {

  private val neverUsedAppNotificationPeriod        = 7.days
  private val neverUsedAppAllowedPeriodOfInactivity = 30.days

  /** The date we should use to find applications that have not been used since. This should be far enough in advance that all required notifications can be sent out.
    */
  private def notificationCutoffDate(allowedPeriodOfInactivity: FiniteDuration, longestNotification: FiniteDuration): Instant =
    instant
      .minus(allowedPeriodOfInactivity.toMillis, ChronoUnit.MILLIS)
      .plus(longestNotification.toMillis, ChronoUnit.MILLIS)

  def notificationCutoffDateForUnusedApps(): Instant    = notificationCutoffDate(deleteUnusedApplicationsAfter(environment), firstNotificationInAdvance(environment))
  def notificationCutoffDateForNeverUsedApps(): Instant = notificationCutoffDate(neverUsedAppAllowedPeriodOfInactivity, neverUsedAppNotificationPeriod)

  override def functionToExecute()(implicit executionContext: ExecutionContext): Future[RunningOfJobSuccessful] = {
    def applicationsToUpdate(knownApplications: List[UnusedApplication], allAppsConsideredFoundAsUnused: Set[ApplicationUsageDetails]): (Set[ApplicationId], Set[ApplicationId]) = {

      val knownApplicationIds: Set[ApplicationId]               = knownApplications.map(_.applicationId).toSet
      val allAppsConsideredFoundAsUnusedIds: Set[ApplicationId] = allAppsConsideredFoundAsUnused.map(_.applicationId)

      (allAppsConsideredFoundAsUnusedIds.diff(knownApplicationIds), knownApplicationIds.diff(allAppsConsideredFoundAsUnusedIds))
    }

    def verifiedAdministratorDetails(adminEmails: Set[LaxEmailAddress]): Future[Map[LaxEmailAddress, Administrator]] = {
      if (adminEmails.isEmpty) {
        Future.successful(Map.empty)
      } else {
        for {
          verifiedAdmins <- thirdPartyDeveloperConnector.fetchVerifiedDevelopers(adminEmails)
        } yield verifiedAdmins.map(admin => admin.email -> Administrator(emailAddress = admin.email, firstName = admin.firstName, lastName = admin.lastName)).toMap
      }
    }

    for {
      knownApplications         <- unusedApplicationsRepository.unusedApplications(environment)
      currentUnusedApplications <- tpoConnector.findApplicationsThatHaveNotBeenUsedSince(environment, notificationCutoffDateForUnusedApps())

      neverUsedSandboxApplications  <- if (environment.isSandbox)
                                         tpoConnector.findApplicationsThatHaveNeverBeenUsedCreatedBefore(Environment.SANDBOX, notificationCutoffDateForNeverUsedApps())
                                       else
                                         Future.successful(List.empty)
      allAppsConsideredFoundAsUnused = (currentUnusedApplications ++ neverUsedSandboxApplications).toSet

      (newlyFoundAppIds, noLongerConsideredAppIds) = applicationsToUpdate(knownApplications, allAppsConsideredFoundAsUnused)

      _                                                                       = logInfo(s"Found ${newlyFoundAppIds.size} new unused applications since last update")
      applicationsToAdd                                                       = allAppsConsideredFoundAsUnused.filter(app => newlyFoundAppIds.contains(app.applicationId))
      verifiedApplicationAdministrators: Map[LaxEmailAddress, Administrator] <- verifiedAdministratorDetails(allAppsConsideredFoundAsUnused.flatMap(_.administrators))
      newUnusedApplicationRecords: Set[UnusedApplication]                     = applicationsToAdd.map(unusedApplicationRecord(_, verifiedApplicationAdministrators))
      insertedCount                                                          <- if (newUnusedApplicationRecords.nonEmpty) unusedApplicationsRepository.bulkInsert(newUnusedApplicationRecords.toSeq) else Future.successful(0)
      expectedInsertionCount                                                  = newUnusedApplicationRecords.size
      _                                                                       = if (insertedCount != expectedInsertionCount) logInfo(s"Successfully inserted $insertedCount new unused applications out of the expected $expectedInsertionCount")

      _ = logInfo(s"Found ${noLongerConsideredAppIds.size} applications that have been used since last update")
      _ = if (noLongerConsideredAppIds.nonEmpty) Future.sequence(noLongerConsideredAppIds.map(unusedApplicationsRepository.deleteUnusedApplicationRecord(environment, _)))
    } yield RunningOfJobSuccessful
  }

  def unusedApplicationRecord(applicationUsageDetails: ApplicationUsageDetails, verifiedAdministratorDetails: Map[LaxEmailAddress, Administrator]): UnusedApplication = {
    val verifiedApplicationAdministrators =
      applicationUsageDetails.administrators.intersect(verifiedAdministratorDetails.keySet).flatMap(verifiedAdministratorDetails.get)

    val lastInteractionDate   = applicationUsageDetails.lastAccessDate.getOrElse(applicationUsageDetails.creationDate).asLocalDate
    val isNeverUsedApp        = applicationUsageDetails.lastAccessDate.fold(true)(_ => false)
    val notificationPeriods   = if (isNeverUsedApp) Set(neverUsedAppNotificationPeriod, 1.day) else sendNotificationsInAdvance(environment)
    val scheduledDeletionDate = LocalDate.now(clock).plusDays(notificationPeriods.max.toDays)
    val notificationSchedule  = notificationPeriods.map(inAdvance => scheduledDeletionDate.minusDays(inAdvance.toDays.toInt))

    UnusedApplication(
      applicationUsageDetails.applicationId,
      applicationUsageDetails.applicationName,
      verifiedApplicationAdministrators.toSeq,
      environment,
      lastInteractionDate,
      notificationSchedule.toSeq,
      scheduledDeletionDate
    )
  }
}

@Singleton
class UpdateUnusedSandboxApplicationRecordsJob @Inject() (
    tpoConnector: ThirdPartyOrchestratorConnector,
    thirdPartyDeveloperConnector: ThirdPartyDeveloperConnector,
    unusedApplicationsRepository: UnusedApplicationsRepository,
    configuration: Configuration,
    clock: Clock,
    lockRepository: LockRepository
  ) extends UpdateUnusedApplicationRecordsJob(
      tpoConnector,
      thirdPartyDeveloperConnector,
      unusedApplicationsRepository,
      Environment.SANDBOX,
      configuration,
      clock,
      lockRepository
    )

@Singleton
class UpdateUnusedProductionApplicationRecordsJob @Inject() (
    tpoConnector: ThirdPartyOrchestratorConnector,
    thirdPartyDeveloperConnector: ThirdPartyDeveloperConnector,
    unusedApplicationsRepository: UnusedApplicationsRepository,
    configuration: Configuration,
    clock: Clock,
    lockRepository: LockRepository
  ) extends UpdateUnusedApplicationRecordsJob(
      tpoConnector,
      thirdPartyDeveloperConnector,
      unusedApplicationsRepository,
      Environment.PRODUCTION,
      configuration,
      clock,
      lockRepository
    )
