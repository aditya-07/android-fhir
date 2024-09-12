package com.google.android.fhir.workflow.activity.phase.request

import com.google.android.fhir.workflow.activity.phase.Phase
import com.google.android.fhir.workflow.activity.resource.request.CPGRequestResource
import com.google.android.fhir.workflow.activity.resource.request.Status
import org.opencds.cqf.fhir.api.Repository

abstract class BaseRequestPhase<R : CPGRequestResource<*>>(val repository: Repository, r: R) :
  Phase.RequestPhase<R> {
  private var request: R = r

  // TODO : Maybe this should return a copy of the resource so that if the user does any changes to this resource, it doesn't affect the state of the flow.
  override fun getRequest() = request

  override fun suspend(reason: String?) = runCatching<Unit> {
    check(request.getStatus() == Status.ACTIVE) {
      " Can't suspend an event with status ${request.getStatus()} "
    }

    request.setStatus(Status.ONHOLD, reason)
    repository.update(request.resource)
  }

  override fun resume() = runCatching<Unit> {
    check(request.getStatus() == Status.ONHOLD) {
      " Can't resume an event with status ${request.getStatus()} "
    }

    request.setStatus(Status.ACTIVE)
    repository.update(request.resource)
  }

  override fun update(r: R) = runCatching<Unit> {
    //TODO Add some basic checks to make sure e is update event and not a completely different resource.
    require(r.getStatus() in listOf(Status.DRAFT, Status.ACTIVE)) {
      "Status is ${r.getStatus()}"
    }
    repository.update(r.resource)
    request = r
  }

  override fun enteredInError(reason: String?) = runCatching<Unit> {
    request.setStatus(Status.ENTEREDINERROR, reason)
    repository.update(request.resource)
  }

  override fun reject(reason: String?) = runCatching<Unit> {
    check(request.getStatus() == Status.ACTIVE) {
      " Can't reject an event with status ${request.getStatus()} "
    }

    request.setStatus(Status.REVOKED, reason)
    repository.update(request.resource)
  }
}