/*
 * Copyright 2022 Google LLC
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

package com.google.android.fhir.sync.download

import com.google.android.fhir.SyncDownloadContext
import com.google.android.fhir.logicalId
import com.google.android.fhir.sync.DataSource
import com.google.android.fhir.sync.DownloadState
import com.google.android.fhir.sync.DownloadWorkManager
import com.google.android.fhir.sync.Request
import com.google.common.truth.Truth.assertThat
import java.util.LinkedList
import java.util.Queue
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.runBlocking
import org.hl7.fhir.exceptions.FHIRException
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Condition
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.OperationOutcome
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.ResourceType
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DownloaderImplTest {

  @Test
  fun `downloader should download all the requests even when some fail`() = runBlocking {
    val requests =
      listOf(
        Request.of("Patient"),
        Request.of("Encounter"),
        Request.of("Medication/med-123-that-fails"),
        Request.of(bundleOf("Observation/ob-123", "Condition/con-123"))
      )

    val downloader = DownloaderImpl(testDataSource, TestDownloadWorkManager(requests))

    val result = mutableListOf<Resource>()
    downloader.download(testSyncDownloadContext).collectIndexed { _, value ->
      if (value is DownloadState.Success) {
        result.addAll(value.resources)
      }
    }

    assertThat(result.map { it.logicalId })
      .containsExactly("pa-123", "en-123", "ob-123", "con-123")
      .inOrder()
  }

  @Test
  fun `downloader should emit all the states for requests whether they pass or fail`() =
    runBlocking {
      val requests =
        listOf(
          Request.of("Patient"),
          Request.of("Encounter"),
          Request.of("Medication/med-123-that-fails"),
          Request.of(bundleOf("Observation/ob-123", "Condition/con-123"))
        )

      val downloader = DownloaderImpl(testDataSource, TestDownloadWorkManager(requests))

      val result = mutableListOf<DownloadState>()
      downloader.download(testSyncDownloadContext).collectIndexed { _, value -> result.add(value) }

      assertThat(result.map { it::class.java })
        .containsExactly(
          DownloadState.Started::class.java,
          DownloadState.Success::class.java,
          DownloadState.Success::class.java,
          DownloadState.Failure::class.java,
          DownloadState.Success::class.java
        )
        .inOrder()

      assertThat(result.filterIsInstance<DownloadState.Success>().map { it.completed })
        .containsExactly(1, 2, 4)
        .inOrder()
    }

  companion object {
    val testSyncDownloadContext =
      object : SyncDownloadContext {
        override suspend fun getLatestTimestampFor(type: ResourceType): String? = null
      }

    private fun bundleOf(vararg getRequest: String) =
      Bundle().apply {
        type = Bundle.BundleType.BATCH
        getRequest.forEach {
          addEntry(
            Bundle.BundleEntryComponent().apply {
              request =
                Bundle.BundleEntryRequestComponent().apply {
                  method = Bundle.HTTPVerb.GET
                  url = it
                }
            }
          )
        }
      }

    private val testDataSource: DataSource =
      object : DataSource {
        override suspend fun download(path: String): Resource {
          return when {
            path == "Patient" ->
              Bundle().apply {
                type = Bundle.BundleType.SEARCHSET
                addEntry(
                  Bundle.BundleEntryComponent().apply {
                    resource = Patient().apply { id = "pa-123" }
                  }
                )
              }
            path == "Encounter" ->
              Bundle().apply {
                type = Bundle.BundleType.SEARCHSET
                addEntry(
                  Bundle.BundleEntryComponent().apply {
                    resource =
                      Encounter().apply {
                        id = "en-123"
                        subject = Reference("Patient/pa-123")
                      }
                  }
                )
              }
            path == "Medication/med-123-that-fails" ->
              OperationOutcome().apply {
                addIssue(
                  OperationOutcome.OperationOutcomeIssueComponent().apply {
                    severity = OperationOutcome.IssueSeverity.FATAL
                    diagnostics = "Resource not found."
                  }
                )
              }
            else -> OperationOutcome()
          }
        }

        override suspend fun download(bundle: Bundle): Resource {
          return Bundle().apply {
            type = Bundle.BundleType.BATCHRESPONSE
            addEntry(
              Bundle.BundleEntryComponent().apply {
                resource =
                  Observation().apply {
                    id = "ob-123"
                    subject = Reference("Patient/pq-123")
                  }
              }
            )
            addEntry(
              Bundle.BundleEntryComponent().apply {
                resource =
                  Condition().apply {
                    id = "con-123"
                    subject = Reference("Patient/pq-123")
                  }
              }
            )
          }
        }

        override suspend fun upload(bundle: Bundle): Resource {
          throw UnsupportedOperationException()
        }
      }
  }
}

class TestDownloadWorkManager(requests: List<Request>) : DownloadWorkManager {
  private val queue: Queue<Request> = LinkedList(requests)

  override suspend fun getNextRequest(context: SyncDownloadContext): Request? = queue.poll()

  override suspend fun getSummaryRequestUrls(context: SyncDownloadContext) =
    emptyMap<ResourceType, String>()

  override suspend fun processResponse(response: Resource): Collection<Resource> {
    if (response is OperationOutcome) {
      throw FHIRException(response.issueFirstRep.diagnostics)
    }
    if (response is Bundle) {
      return response.entry.map { it.resource }
    }
    return emptyList()
  }
}
