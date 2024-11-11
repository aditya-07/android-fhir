/*
 * Copyright 2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.fhir.workflow.demo.ui.main

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ca.uhn.fhir.context.FhirContext
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.FhirEngineProvider
import com.google.android.fhir.knowledge.KnowledgeManager
import com.google.android.fhir.search.search
import com.google.android.fhir.workflow.FhirOperator
import com.google.android.fhir.workflow.activity.ActivityFlow
import com.google.android.fhir.workflow.activity.resource.event.CPGEventResource
import com.google.android.fhir.workflow.activity.resource.event.CPGMedicationDispenseEvent
import com.google.android.fhir.workflow.activity.resource.request.CPGMedicationRequest
import com.google.android.fhir.workflow.activity.resource.request.CPGRequestResource
import com.google.android.fhir.workflow.repositories.FhirEngineRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.hl7.fhir.r4.model.MedicationDispense
import org.hl7.fhir.r4.model.MedicationRequest
import org.hl7.fhir.r4.model.Resource
import org.opencds.cqf.fhir.api.Repository

class MainViewModel(private val application: Application) : AndroidViewModel(application) {

  private val fhirEngine: FhirEngine by lazy {
    FhirEngineProvider.getInstance(application.applicationContext)
  }

  private val repository: Repository by lazy {
    FhirEngineRepository(FhirContext.forR4Cached(), fhirEngine)
  }
  private val enabledPhaseFlow = MutableStateFlow(FlowPhase.PROPOSAL)
  private val activityOptionFlow = MutableStateFlow(MEDICATION_DISPENSE)
  private val _progressFlow = MutableStateFlow(false)
  val progressFlow: Flow<Boolean> = _progressFlow
  private var activityHandler: ActivityHandler? = null
  private var proposal: Resource? = null
  private var plan: Resource? = null
  private var order: Resource? = null
  private var perform: Resource? = null

  private val proposalHandler: ProposalCreationHandler by lazy {
    val knowledgeManager =
      KnowledgeManager.create(context = application.applicationContext, inMemory = false)

    val fhirOperator =
      FhirOperator.Builder(application.applicationContext)
        .fhirEngine(fhirEngine)
        .fhirContext(
          FhirContext.forR4Cached(),
        )
        .knowledgeManager(knowledgeManager)
        .build()

    ProposalCreationHandler(
      fhirEngine,
      fhirOperator,
      application.applicationContext,
      knowledgeManager,
    )
  }

  val adapterData =
    enabledPhaseFlow.map {
      Log.d("TAG", "phaseFlow.map: $it")
      val nextPhase = loadChainAndReturnNextPhase()
      enabledPhaseFlow.value = nextPhase

      // Initialize the activity flow
      if (activityHandler == null) {
        val resource =
          if (order != null) {
            order
          } else if (plan != null) {
            plan
          } else if (proposal != null) {
            proposal
          } else {
            null
          }

        resource?.let {
          activityHandler =
            perform?.let {
              ActivityHandler(
                ActivityFlow.of(repository, CPGMedicationDispenseEvent(it as MedicationDispense))
                  as ActivityFlow<CPGRequestResource<*>, CPGEventResource<*>>,
              )
            }
              ?: ActivityHandler(
                ActivityFlow.of(repository, CPGRequestResource.of(it) as CPGMedicationRequest)
                  as ActivityFlow<CPGRequestResource<*>, CPGEventResource<*>>,
              )
        }
      }
      generateData(nextPhase, ::handleOnClick)
    }

  private fun handleOnClick(phase: FlowPhase) {
    Log.d("TAG", "handleOnClick: $phase")
    viewModelScope.launch(Dispatchers.IO) {
      when (phase) {
        FlowPhase.PROPOSAL -> {
          _progressFlow.value = true
          createProposalPhase()
          _progressFlow.value = false
        }
        FlowPhase.PLAN -> {
          _progressFlow.value = true
          createPlanPhase()
          _progressFlow.value = false
        }
        FlowPhase.ORDER -> {
          _progressFlow.value = true
          createOrderPhase()
          _progressFlow.value = false
        }
        FlowPhase.PERFORM -> {
          _progressFlow.value = true
          createPerformPhase()
          _progressFlow.value = false
        }
        FlowPhase.NONE -> TODO()
      }
    }
  }

  init {

    viewModelScope.launch {
      activityOptionFlow.collect {
        activityHandler = null
        enabledPhaseFlow.value = FlowPhase.PROPOSAL
      }
    }
  }

  private suspend fun createProposalPhase() {
    proposalHandler
      .prepareAndInitiateProposal(activityOptionFlow.value)
      .onSuccess {
        activityHandler =
          ActivityHandler(
            ActivityFlow.of(repository, CPGRequestResource.of(it as MedicationRequest))
              as ActivityFlow<CPGRequestResource<*>, CPGEventResource<*>>,
          )
        onPhaseCreatedSuccess(FlowPhase.PROPOSAL)
      }
      .onFailure { it.printStackTrace() }
  }

  private fun onPhaseCreatedSuccess(phase: FlowPhase) {
    val nextPhase =
      when (phase) {
        FlowPhase.PROPOSAL -> FlowPhase.PLAN
        FlowPhase.PLAN -> FlowPhase.ORDER
        FlowPhase.ORDER -> FlowPhase.PERFORM
        FlowPhase.PERFORM -> FlowPhase.NONE
        FlowPhase.NONE -> null
      }
    if (nextPhase != null) enabledPhaseFlow.value = nextPhase
  }

  private suspend fun createPlanPhase() {
    activityHandler!!
      .prepareAndInitiatePlan()
      .onSuccess { onPhaseCreatedSuccess(FlowPhase.PLAN) }
      .onFailure { it.printStackTrace() }
  }

  private suspend fun createOrderPhase() {
    activityHandler!!
      .prepareAndInitiateOrder()
      .onSuccess { onPhaseCreatedSuccess(FlowPhase.ORDER) }
      .onFailure { it.printStackTrace() }
  }

  private suspend fun createPerformPhase() {
    activityHandler!!
      .prepareAndInitiatePerform(
        CPGMedicationDispenseEvent::class.java as Class<CPGEventResource<*>>,
      )
      .onSuccess { onPhaseCreatedSuccess(FlowPhase.PERFORM) }
      .onFailure { it.printStackTrace() }
  }

  private suspend fun loadChainAndReturnNextPhase(): FlowPhase {
    val request =
      fhirEngine.search<MedicationRequest> {
        filter(MedicationRequest.SUBJECT, { value = "Patient/active_apple_guy" })
      }

    val request2 =
      fhirEngine.search<MedicationDispense> {
        filter(MedicationRequest.SUBJECT, { value = "Patient/active_apple_guy" })
      }

    // take the last resource and move backwards

    proposal = null
    plan = null
    order = null
    perform = null
    request.forEach {
      if (it.resource.intent == MedicationRequest.MedicationRequestIntent.PROPOSAL) {
        proposal = it.resource
      } else if (it.resource.intent == MedicationRequest.MedicationRequestIntent.PLAN) {
        plan = it.resource
      } else if (it.resource.intent == MedicationRequest.MedicationRequestIntent.ORDER) {
        order = it.resource
      } else {
        // perform = it.resource
      }
    }

    perform = request2.firstOrNull()?.resource

    return if (proposal == null) {
      FlowPhase.PROPOSAL
    } else if (plan == null) {
      FlowPhase.PLAN
    } else if (order == null) {
      FlowPhase.ORDER
    } else if (perform == null) {
      FlowPhase.PERFORM
    } else {
      FlowPhase.NONE
    }
  }

  private fun generateData(
    phase: FlowPhase,
    onclick: (phase: FlowPhase) -> Unit,
  ): List<DataModel> {
    val list = mutableListOf<DataModel>()
    list.add(
      DataModel(
        FlowPhase.PROPOSAL,
        getPhaseDetails(proposal),
        phase == FlowPhase.PROPOSAL,
        onclick,
      ),
    )
    list.add(
      DataModel(
        FlowPhase.PLAN,
        getPhaseDetails(plan),
        phase == FlowPhase.PLAN,
        onclick,
      ),
    )
    list.add(
      DataModel(
        FlowPhase.ORDER,
        getPhaseDetails(order),
        phase == FlowPhase.ORDER,
        onclick,
      ),
    )
    list.add(
      DataModel(FlowPhase.PERFORM, getPhaseDetails(perform), phase == FlowPhase.PERFORM, onclick),
    )
    return list
  }

  //  TODO: CPGRequestResource : make getIntent public and then uncomment this.
  //  private fun getPhaseDetails(requestResource: Resource?): String {
  //    return if (requestResource == null) {
  //      ""
  //    } else if (requestResource is MedicationRequest) {
  //      val cpgRequestResource = CPGRequestResource.of(requestResource)
  //
  //      val dosage =
  //        FhirContext.forR4Cached()
  //          .newJsonParser()
  //          .encodeToString(requestResource.dosageInstruction.first())
  //
  //      """
  //        ID     : ${cpgRequestResource.resourceType}/${cpgRequestResource.logicalId}
  //        Intent : ${cpgRequestResource.getIntent().code}
  //        Status : ${cpgRequestResource.getStatusCode()}
  //        BasedOn: ${cpgRequestResource.getBasedOn()?.reference}
  //
  //        Additional Info: $dosage
  //            """
  //        .trimIndent()
  //    } else if (requestResource is MedicationDispense) {
  //      val cpgRequestResource = CPGMedicationDispenseEvent(requestResource)
  //
  //      val dosage =
  //        FhirContext.forR4Cached()
  //          .newJsonParser()
  //          .encodeToString(requestResource.dosageInstruction.first())
  //
  //      """
  //        ID     : ${cpgRequestResource.resourceType}/${cpgRequestResource.logicalId}
  //        Status : ${cpgRequestResource.getStatusCode()}
  //        BasedOn: ${cpgRequestResource.getBasedOn()?.reference}
  //
  //        Additional Info: $dosage
  //            """
  //        .trimIndent()
  //    } else {
  //      ""
  //    }
  //  }

  private fun getPhaseDetails(requestResource: Resource?): String {
    return if (requestResource == null) {
      ""
    } else if (requestResource is MedicationRequest) {
      val dosage =
        FhirContext.forR4Cached()
          .newJsonParser()
          .encodeToString(requestResource.dosageInstruction.first())

      """
        ID     : ${requestResource.resourceType}/${requestResource.logicalId}
        Intent : ${requestResource.intent.display}
        Status : ${requestResource.status.display}
        BasedOn: ${requestResource.getBasedOn().firstOrNull()?.reference}
        
        Additional Info: $dosage
            """
        .trimIndent()
    } else if (requestResource is MedicationDispense) {
      val dosage =
        FhirContext.forR4Cached()
          .newJsonParser()
          .encodeToString(requestResource.dosageInstruction.first())

      """
        ID     : ${requestResource.resourceType}/${requestResource.logicalId}
        Status : ${requestResource.status.display}
        BasedOn: ${requestResource.authorizingPrescription.firstOrNull()?.reference}
        
        Additional Info: $dosage
            """
        .trimIndent()
    } else {
      ""
    }
  }

  fun selectFlow(id: Int) {
    if (id == com.google.android.fhir.workflow.demo.R.id.menu_order_medication) {
      activityOptionFlow.value = MEDICATION_DISPENSE
    }
  }

  fun restartFlow() {
    viewModelScope.launch {
      _progressFlow.value = true
      cleanUp()
      enabledPhaseFlow.value = FlowPhase.PROPOSAL
      _progressFlow.value = false
    }
  }

  private suspend fun cleanUp() =
    withContext(Dispatchers.IO) {
      proposal?.let { fhirEngine.delete(it.resourceType, it.logicalId) }

      plan?.let { fhirEngine.delete(it.resourceType, it.logicalId) }

      order?.let { fhirEngine.delete(it.resourceType, it.logicalId) }

      perform?.let { fhirEngine.delete(it.resourceType, it.logicalId) }

      activityHandler = null
    }
}

data class Configuration(
  val id: String,
  val description: String,
  val planDefinitionPath: String?,
  val activityDefinitionPath: String?,
  val cqlLibraryPath: String?,
  val inputBundlePath: String?,
)

val MEDICATION_DISPENSE =
  Configuration(
    id = "id_medication_dispense",
    description = "Apple a day",
    planDefinitionPath = "pd/DailyAppleRecommendation.json",
    activityDefinitionPath = "ad/DailyAppleActivity.json",
    cqlLibraryPath = "cql/DailyAppleLogic.cql",
    inputBundlePath = null,
  )

val Resource.logicalId: String
  get() {
    return this.idElement?.idPart.orEmpty()
  }

enum class FlowPhase {
  PROPOSAL,
  PLAN,
  ORDER,
  PERFORM,
  NONE,
}
