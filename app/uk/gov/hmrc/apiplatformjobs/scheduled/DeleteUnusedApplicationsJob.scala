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

import javax.inject.{Inject, Named, Singleton}
import play.api.Configuration
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.apiplatformjobs.connectors.ThirdPartyApplicationConnector
import uk.gov.hmrc.apiplatformjobs.models.Environment
import uk.gov.hmrc.apiplatformjobs.models.Environment.Environment
import uk.gov.hmrc.apiplatformjobs.repository.UnusedApplicationsRepository

import scala.concurrent.{ExecutionContext, Future}

abstract class DeleteUnusedApplicationsJob(environment: Environment,
                                           thirdPartyApplicationConnector: ThirdPartyApplicationConnector,
                                           unusedApplicationsRepository: UnusedApplicationsRepository,
                                           configuration: Configuration,
                                           mongo: ReactiveMongoComponent)
  extends TimedJob(s"DeleteUnusedApplications-$environment", configuration, mongo) {

  override def functionToExecute()(implicit executionContext: ExecutionContext): Future[RunningOfJobSuccessful] = {
    Future.successful(RunningOfJobSuccessful)
  }
}
@Singleton
class DeleteUnusedSandboxApplicationsJob @Inject()(@Named("tpa-sandbox") thirdPartyApplicationConnector: ThirdPartyApplicationConnector,
                                                   unusedApplicationsRepository: UnusedApplicationsRepository,
                                                   configuration: Configuration,
                                                   mongo: ReactiveMongoComponent)
  extends DeleteUnusedApplicationsJob(Environment.SANDBOX, thirdPartyApplicationConnector, unusedApplicationsRepository, configuration, mongo)

@Singleton
class DeleteUnusedProductionApplicationsJob @Inject()(@Named("tpa-production") thirdPartyApplicationConnector: ThirdPartyApplicationConnector,
                                                      unusedApplicationsRepository: UnusedApplicationsRepository,
                                                      configuration: Configuration,
                                                      mongo: ReactiveMongoComponent)
  extends DeleteUnusedApplicationsJob(Environment.PRODUCTION, thirdPartyApplicationConnector, unusedApplicationsRepository, configuration, mongo)