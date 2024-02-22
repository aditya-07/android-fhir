/*
 * Copyright 2023-2024 Google LLC
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

package com.google.android.fhir.sync.upload.patch

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.FhirVersionEnum
import com.google.android.fhir.LocalChange
import com.google.android.fhir.LocalChangeToken
import com.google.android.fhir.db.Database
import com.google.android.fhir.db.LocalChangeResourceReference
import com.google.android.fhir.db.impl.dao.diff
import com.google.android.fhir.db.impl.entities.LocalChangeEntity
import com.google.android.fhir.logicalId
import com.google.android.fhir.testing.assertJsonArrayEqualsIgnoringOrder
import com.google.android.fhir.testing.jsonParser
import com.google.android.fhir.testing.readFromFile
import com.google.android.fhir.testing.readJsonArrayFromFile
import com.google.android.fhir.toLocalChange
import com.google.android.fhir.versionId
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.util.Date
import java.util.LinkedList
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.Group
import org.hl7.fhir.r4.model.HumanName
import org.hl7.fhir.r4.model.Meta
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.RelatedPerson
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.ResourceType
import org.json.JSONArray
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PerResourcePatchGeneratorTest {

  @Mock private lateinit var database: Database
  private lateinit var patchGenerator: PerResourcePatchGenerator

  @Before
  fun setUp() {
    MockitoAnnotations.openMocks(this)
    runTest { whenever(database.getLocalChangeResourceReferences(any())).thenReturn(emptyList()) }
    patchGenerator = PerResourcePatchGenerator(database)
  }

  @Test
  fun `should generate a single insert patch if the resource is inserted`() = runTest {
    val patient: Patient = readFromFile(Patient::class.java, "/date_test_patient.json")
    val insertionLocalChange = createInsertLocalChange(patient)

    val patches = patchGenerator.generate(listOf(insertionLocalChange))

    with(patches.single()) {
      with(generatedPatch) {
        assertThat(type).isEqualTo(Patch.Type.INSERT)
        assertThat(resourceId).isEqualTo(patient.logicalId)
        assertThat(resourceType).isEqualTo(patient.resourceType.name)
        assertThat(payload).isEqualTo(jsonParser.encodeResourceToString(patient))
      }

      with(localChanges) {
        assertThat(this).hasSize(1)
        assertThat(this[0]).isEqualTo(insertionLocalChange)
      }
    }
  }

  @Test
  fun `should generate a single update patch if the resource is updated`() = runTest {
    val remoteMeta =
      Meta().apply {
        versionId = "patient-version-1"
        lastUpdated = Date()
      }
    val remotePatient: Patient = readFromFile(Patient::class.java, "/date_test_patient.json")
    remotePatient.meta = remoteMeta
    val updatedPatient1 = readFromFile(Patient::class.java, "/update_test_patient_1.json")
    updatedPatient1.meta = remoteMeta
    val updateLocalChange1 = createUpdateLocalChange(remotePatient, updatedPatient1, 1L)
    val updatePatch = readJsonArrayFromFile("/update_patch_1.json")

    val patches = patchGenerator.generate(listOf(updateLocalChange1))

    with(patches.single()) {
      with(generatedPatch) {
        assertThat(type).isEqualTo(Patch.Type.UPDATE)
        assertThat(resourceId).isEqualTo(remotePatient.logicalId)
        assertThat(resourceType).isEqualTo(remotePatient.resourceType.name)
        assertThat(versionId).isEqualTo(remoteMeta.versionId)
        assertJsonArrayEqualsIgnoringOrder(JSONArray(payload), updatePatch)
      }

      with(localChanges) {
        assertThat(this).hasSize(1)
        assertThat(this[0]).isEqualTo(updateLocalChange1)
      }
    }
  }

  @Test
  fun `should generate a single delete patch if the resource is deleted`() = runTest {
    val remoteMeta =
      Meta().apply {
        versionId = "patient-version-1"
        lastUpdated = Date()
      }
    val remotePatient: Patient = readFromFile(Patient::class.java, "/date_test_patient.json")
    remotePatient.meta = remoteMeta
    val deleteLocalChange = createDeleteLocalChange(remotePatient, 3L)

    val patches = patchGenerator.generate(listOf(deleteLocalChange))

    with(patches.single()) {
      with(generatedPatch) {
        assertThat(type).isEqualTo(Patch.Type.DELETE)
        assertThat(resourceId).isEqualTo(remotePatient.logicalId)
        assertThat(resourceType).isEqualTo(remotePatient.resourceType.name)
        assertThat(versionId).isEqualTo(remoteMeta.versionId)
        assertThat(payload).isEmpty()
      }

      with(localChanges) {
        assertThat(this).hasSize(1)
        assertThat(this[0]).isEqualTo(deleteLocalChange)
      }
    }
  }

  @Test
  fun `should generate a single insert patch if the resource is inserted and updated`() = runTest {
    val patient: Patient = readFromFile(Patient::class.java, "/date_test_patient.json")
    val insertionLocalChange = createInsertLocalChange(patient)
    val updatedPatient = readFromFile(Patient::class.java, "/update_test_patient_1.json")
    val updateLocalChange = createUpdateLocalChange(patient, updatedPatient, 1L)
    val patientString = jsonParser.encodeResourceToString(updatedPatient)

    val patches = patchGenerator.generate(listOf(insertionLocalChange, updateLocalChange))

    with(patches.single()) {
      with(generatedPatch) {
        assertThat(type).isEqualTo(Patch.Type.INSERT)
        assertThat(resourceId).isEqualTo(patient.logicalId)
        assertThat(resourceType).isEqualTo(patient.resourceType.name)
        assertThat(payload).isEqualTo(patientString)
      }

      with(localChanges) {
        assertThat(this).hasSize(2)
        assertThat(this).containsExactly(insertionLocalChange, updateLocalChange)
      }
    }
  }

  @Test
  fun `should generate no patch if the resource is inserted and deleted`() = runTest {
    val changes =
      listOf(
        LocalChangeEntity(
            id = 1,
            resourceType = ResourceType.Patient.name,
            resourceId = "Patient-001",
            resourceUuid = UUID.randomUUID(),
            type = LocalChangeEntity.Type.INSERT,
            payload =
              FhirContext.forCached(FhirVersionEnum.R4)
                .newJsonParser()
                .encodeResourceToString(
                  Patient().apply {
                    id = "Patient-001"
                    addName(
                      HumanName().apply {
                        addGiven("John")
                        family = "Doe"
                      },
                    )
                  },
                ),
            timestamp = Instant.now(),
          )
          .toLocalChange()
          .apply { LocalChangeToken(listOf(1)) },
        LocalChangeEntity(
            id = 2,
            resourceType = ResourceType.Patient.name,
            resourceId = "Patient-001",
            resourceUuid = UUID.randomUUID(),
            type = LocalChangeEntity.Type.DELETE,
            payload = "",
            timestamp = Instant.now(),
          )
          .toLocalChange()
          .apply { LocalChangeToken(listOf(2)) },
      )
    val patchToUpload = patchGenerator.generate(changes)

    assertThat(patchToUpload).isEmpty()
  }

  @Test
  fun `should generate no patch if the resource is inserted, updated, and deleted`() = runTest {
    val changes =
      listOf(
        LocalChangeEntity(
            id = 1,
            resourceType = ResourceType.Patient.name,
            resourceId = "Patient-001",
            resourceUuid = UUID.randomUUID(),
            type = LocalChangeEntity.Type.INSERT,
            payload =
              FhirContext.forCached(FhirVersionEnum.R4)
                .newJsonParser()
                .encodeResourceToString(
                  Patient().apply {
                    id = "Patient-001"
                    addName(
                      HumanName().apply {
                        addGiven("John")
                        family = "Doe"
                      },
                    )
                  },
                ),
            timestamp = Instant.now(),
          )
          .toLocalChange()
          .apply { LocalChangeToken(listOf(1)) },
        LocalChangeEntity(
            id = 2,
            resourceType = ResourceType.Patient.name,
            resourceId = "Patient-001",
            resourceUuid = UUID.randomUUID(),
            type = LocalChangeEntity.Type.UPDATE,
            payload =
              diff(
                  jsonParser,
                  Patient().apply {
                    id = "Patient-001"
                    addName(
                      HumanName().apply {
                        addGiven("Jane")
                        family = "Doe"
                      },
                    )
                  },
                  Patient().apply {
                    id = "Patient-001"
                    addName(
                      HumanName().apply {
                        addGiven("Janet")
                        family = "Doe"
                      },
                    )
                  },
                )
                .toString(),
            timestamp = Instant.now(),
          )
          .toLocalChange()
          .apply { LocalChangeToken(listOf(1)) },
        LocalChangeEntity(
            id = 3,
            resourceType = ResourceType.Patient.name,
            resourceId = "Patient-001",
            resourceUuid = UUID.randomUUID(),
            type = LocalChangeEntity.Type.DELETE,
            payload = "",
            timestamp = Instant.now(),
          )
          .toLocalChange()
          .apply { LocalChangeToken(listOf(3)) },
      )
    val patchToUpload = patchGenerator.generate(changes)

    assertThat(patchToUpload).isEmpty()
  }

  @Test
  fun `should generate a single update patch if the resource is updated twice`() = runTest {
    val remoteMeta =
      Meta().apply {
        versionId = "patient-version-1"
        lastUpdated = Date()
      }
    val remotePatient: Patient = readFromFile(Patient::class.java, "/date_test_patient.json")
    remotePatient.meta = remoteMeta
    val updatedPatient1 = readFromFile(Patient::class.java, "/update_test_patient_1.json")
    updatedPatient1.meta = remoteMeta
    val updateLocalChange1 = createUpdateLocalChange(remotePatient, updatedPatient1, 1L)
    val updatedPatient2 = readFromFile(Patient::class.java, "/update_test_patient_2.json")
    updatedPatient2.meta = remoteMeta
    val updateLocalChange2 = createUpdateLocalChange(updatedPatient1, updatedPatient2, 2L)
    val updatePatch = readJsonArrayFromFile("/update_patch_2.json")

    val patches = patchGenerator.generate(listOf(updateLocalChange1, updateLocalChange2))

    with(patches.single()) {
      with(generatedPatch) {
        assertThat(type).isEqualTo(Patch.Type.UPDATE)
        assertThat(resourceId).isEqualTo(remotePatient.logicalId)
        assertThat(resourceType).isEqualTo(remotePatient.resourceType.name)
        assertThat(versionId).isEqualTo(remoteMeta.versionId)
        assertJsonArrayEqualsIgnoringOrder(JSONArray(payload), updatePatch)
      }

      with(localChanges) {
        assertThat(size).isEqualTo(2)
        assertThat(this).containsExactly(updateLocalChange1, updateLocalChange2)
      }
    }
  }

  @Test
  fun `should generate a single update patch with three elements of two adds and one remove`() =
    runTest {
      val expectedPatch = readJsonArrayFromFile("/update_careplan_patch.json")
      val updatePatch1 = readJsonArrayFromFile("/update_careplan_patch_1.json")
      val updatePatch2 = readJsonArrayFromFile("/update_careplan_patch_2.json")

      val updatedLocalChange1 =
        LocalChange(
          resourceType = "CarePlan",
          resourceId = "131b5257-a8b3-435a-8cb3-4cb1296be24a",
          type = LocalChange.Type.UPDATE,
          payload = updatePatch1.toString(),
          timestamp = Instant.now(),
          token = LocalChangeToken(listOf(1)),
        )

      val updatedLocalChange2 =
        LocalChange(
          resourceType = "CarePlan",
          resourceId = "131b5257-a8b3-435a-8cb3-4cb1296be24a",
          type = LocalChange.Type.UPDATE,
          payload = updatePatch2.toString(),
          timestamp = Instant.now(),
          token = LocalChangeToken(listOf(1)),
        )

      val patches = patchGenerator.generate(listOf(updatedLocalChange1, updatedLocalChange2))

      with(patches.single().generatedPatch) {
        assertThat(type).isEqualTo(Patch.Type.UPDATE)
        assertThat(resourceId).isEqualTo("131b5257-a8b3-435a-8cb3-4cb1296be24a")
        assertThat(resourceType).isEqualTo("CarePlan")
        assertJsonArrayEqualsIgnoringOrder(JSONArray(payload), expectedPatch)
      }
    }

  @Test
  fun `should generate a single delete patch if the resource is updated and deleted`() = runTest {
    val remoteMeta =
      Meta().apply {
        versionId = "patient-version-1"
        lastUpdated = Date()
      }
    val remotePatient: Patient = readFromFile(Patient::class.java, "/date_test_patient.json")
    remotePatient.meta = remoteMeta
    val updatedPatient1 = remotePatient.copy()
    updatedPatient1.name = listOf(HumanName().addGiven("John").setFamily("Doe"))
    val updateLocalChange1 = createUpdateLocalChange(remotePatient, updatedPatient1, 1L)
    val updatedPatient2 = updatedPatient1.copy()
    updatedPatient2.name = listOf(HumanName().addGiven("Jimmy").setFamily("Doe"))
    val updateLocalChange2 = createUpdateLocalChange(updatedPatient1, updatedPatient2, 2L)
    val deleteLocalChange = createDeleteLocalChange(updatedPatient2, 3L)

    val patches =
      patchGenerator.generate(
        listOf(updateLocalChange1, updateLocalChange2, deleteLocalChange),
      )

    with(patches.single()) {
      with(generatedPatch) {
        assertThat(type).isEqualTo(Patch.Type.DELETE)
        assertThat(resourceId).isEqualTo(remotePatient.logicalId)
        assertThat(resourceType).isEqualTo(remotePatient.resourceType.name)
        assertThat(versionId).isEqualTo(remoteMeta.versionId)
        assertThat(payload).isEmpty()
      }

      with(localChanges) {
        assertThat(size).isEqualTo(3)
        assertThat(this).containsExactly(updateLocalChange1, updateLocalChange2, deleteLocalChange)
      }
    }
  }

  @Test
  fun `should throw an error if a change is done after a resource is deleted locally`() = runTest {
    val changes =
      listOf(
        LocalChangeEntity(
            id = 2,
            resourceType = ResourceType.Patient.name,
            resourceId = "Patient-001",
            type = LocalChangeEntity.Type.DELETE,
            payload = "",
            resourceUuid = UUID.randomUUID(),
            timestamp = Instant.now(),
          )
          .toLocalChange()
          .apply { LocalChangeToken(listOf(2)) },
        LocalChangeEntity(
            id = 3,
            resourceType = ResourceType.Patient.name,
            resourceId = "Patient-001",
            type = LocalChangeEntity.Type.UPDATE,
            payload = "",
            resourceUuid = UUID.randomUUID(),
            timestamp = Instant.now(),
          )
          .toLocalChange()
          .apply { LocalChangeToken(listOf(3)) },
      )

    val errorMessage =
      assertFailsWith<IllegalArgumentException> { patchGenerator.generate(changes) }
        .localizedMessage

    assertThat(errorMessage).isEqualTo("Changes after deletion of resource are not permitted")
  }

  @Test
  fun `should throw an error if a change is done before a resource is created locally`() = runTest {
    val changes =
      listOf(
        LocalChangeEntity(
            id = 3,
            resourceType = ResourceType.Patient.name,
            resourceId = "Patient-001",
            type = LocalChangeEntity.Type.UPDATE,
            payload = "",
            resourceUuid = UUID.randomUUID(),
            timestamp = Instant.now(),
          )
          .toLocalChange()
          .apply { LocalChangeToken(listOf(1)) },
        LocalChangeEntity(
            id = 1,
            resourceType = ResourceType.Patient.name,
            resourceId = "Patient-001",
            resourceUuid = UUID.randomUUID(),
            type = LocalChangeEntity.Type.INSERT,
            payload =
              FhirContext.forCached(FhirVersionEnum.R4)
                .newJsonParser()
                .encodeResourceToString(
                  Patient().apply {
                    id = "Patient-001"
                    addName(
                      HumanName().apply {
                        addGiven("John")
                        family = "Doe"
                      },
                    )
                  },
                ),
            timestamp = Instant.now(),
          )
          .toLocalChange()
          .apply { LocalChangeToken(listOf(2)) },
      )
    val errorMessage =
      assertFailsWith<IllegalArgumentException> { patchGenerator.generate(changes) }
        .localizedMessage

    assertThat(errorMessage).isEqualTo("Changes before creation of resource are not permitted")
  }

  private fun createUpdateLocalChange(
    oldEntity: Resource,
    updatedResource: Resource,
    currentChangeId: Long,
  ): LocalChange {
    val jsonDiff = diff(jsonParser, oldEntity, updatedResource)
    return LocalChange(
      resourceId = oldEntity.logicalId,
      resourceType = oldEntity.resourceType.name,
      type = LocalChange.Type.UPDATE,
      payload = jsonDiff.toString(),
      versionId = oldEntity.versionId,
      token = LocalChangeToken(listOf(currentChangeId + 1)),
      timestamp = Instant.now(),
    )
  }

  private fun createInsertLocalChange(entity: Resource, currentChangeId: Long = 1): LocalChange {
    return LocalChange(
      resourceId = entity.logicalId,
      resourceType = entity.resourceType.name,
      type = LocalChange.Type.INSERT,
      payload = jsonParser.encodeResourceToString(entity),
      versionId = entity.versionId,
      token = LocalChangeToken(listOf(currentChangeId)),
      timestamp = Instant.now(),
    )
  }

  private fun createDeleteLocalChange(entity: Resource, currentChangeId: Long): LocalChange {
    return LocalChange(
      resourceId = entity.logicalId,
      resourceType = entity.resourceType.name,
      type = LocalChange.Type.DELETE,
      payload = "",
      versionId = entity.versionId,
      token = LocalChangeToken(listOf(currentChangeId + 1)),
      timestamp = Instant.now(),
    )
  }

  @Test
  fun `createReferenceAdjacencyList with local changes for only new resources should only have edges to inserted resources`() =
    runTest {
      val localChanges = LinkedList<LocalChange>()
      val localChangeResourceReferences = mutableListOf<LocalChangeResourceReference>()

      var group =
        Group()
          .apply {
            id = "group-1"
            type = Group.GroupType.PERSON
          }
          .also { localChanges.add(createInsertLocalChange(it, Random.nextLong())) }

      // pa-01
      Patient()
        .apply { id = "patient-1" }
        .also { localChanges.add(createInsertLocalChange(it, Random.nextLong())) }
      Encounter()
        .apply {
          id = "encounter-1"
          subject = Reference("Patient/patient-1")
        }
        .also {
          localChanges.add(createInsertLocalChange(it, Random.nextLong()))
          localChangeResourceReferences.add(
            LocalChangeResourceReference(
              localChanges.last().token.ids.first(),
              "Patient/patient-1",
              "Encounter.subject",
            ),
          )
        }

      Observation()
        .apply {
          id = "observation-1"
          subject = Reference("Patient/patient-1")
          encounter = Reference("Encounter/encounter-1")
        }
        .also {
          localChanges.add(createInsertLocalChange(it, Random.nextLong()))
          localChangeResourceReferences.add(
            LocalChangeResourceReference(
              localChanges.last().token.ids.first(),
              "Patient/patient-1",
              "Observation.subject",
            ),
          )
          localChangeResourceReferences.add(
            LocalChangeResourceReference(
              localChanges.last().token.ids.first(),
              "Encounter/encounter-1",
              "Observation.encounter",
            ),
          )
        }

      group =
        group
          .copy()
          .apply { addMember(Group.GroupMemberComponent(Reference("Patient/patient-1"))) }
          .also {
            localChanges.add(createUpdateLocalChange(group, it, Random.nextLong()))
            localChangeResourceReferences.add(
              LocalChangeResourceReference(
                localChanges.last().token.ids.first(),
                "Patient/patient-1",
                "Group.member",
              ),
            )
          }

      // pa-02
      Patient()
        .apply { id = "patient-2" }
        .also { localChanges.add(createInsertLocalChange(it, Random.nextLong())) }
      Encounter()
        .apply {
          id = "encounter-2"
          subject = Reference("Patient/patient-2")
        }
        .also {
          localChanges.add(createInsertLocalChange(it, Random.nextLong()))
          localChangeResourceReferences.add(
            LocalChangeResourceReference(
              localChanges.last().token.ids.first(),
              "Patient/patient-2",
              "Encounter.subject",
            ),
          )
        }

      Observation()
        .apply {
          id = "observation-2"
          subject = Reference("Patient/patient-2")
          encounter = Reference("Encounter/encounter-2")
        }
        .also {
          localChanges.add(createInsertLocalChange(it, Random.nextLong()))
          localChangeResourceReferences.add(
            LocalChangeResourceReference(
              localChanges.last().token.ids.first(),
              "Patient/patient-2",
              "Observation.subject",
            ),
          )
          localChangeResourceReferences.add(
            LocalChangeResourceReference(
              localChanges.last().token.ids.first(),
              "Encounter/encounter-2",
              "Observation.encounter",
            ),
          )
        }

      group =
        group
          .copy()
          .apply { addMember(Group.GroupMemberComponent(Reference("Patient/patient-2"))) }
          .also {
            localChanges.add(createUpdateLocalChange(group, it, Random.nextLong()))
            localChangeResourceReferences.add(
              LocalChangeResourceReference(
                localChanges.last().token.ids.first(),
                "Patient/patient-2",
                "Group.member",
              ),
            )
          }

      // pa-03
      Patient()
        .apply { id = "patient-3" }
        .also { localChanges.add(createInsertLocalChange(it, Random.nextLong())) }

      Encounter()
        .apply {
          id = "encounter-3"
          subject = Reference("Patient/patient-3")
        }
        .also {
          localChanges.add(createInsertLocalChange(it, Random.nextLong()))
          localChangeResourceReferences.add(
            LocalChangeResourceReference(
              localChanges.last().token.ids.first(),
              "Patient/patient-3",
              "Encounter.subject",
            ),
          )
        }

      Observation()
        .apply {
          id = "observation-3"
          subject = Reference("Patient/patient-3")
          encounter = Reference("Encounter/encounter-3")
        }
        .also {
          localChanges.add(createInsertLocalChange(it, Random.nextLong()))
          localChangeResourceReferences.add(
            LocalChangeResourceReference(
              localChanges.last().token.ids.first(),
              "Patient/patient-3",
              "Observation.subject",
            ),
          )
          localChangeResourceReferences.add(
            LocalChangeResourceReference(
              localChanges.last().token.ids.first(),
              "Encounter/encounter-3",
              "Observation.encounter",
            ),
          )
        }

      group =
        group
          .copy()
          .apply { addMember(Group.GroupMemberComponent(Reference("Patient/patient-3"))) }
          .also {
            localChanges.add(createUpdateLocalChange(group, it, Random.nextLong()))
            localChangeResourceReferences.add(
              LocalChangeResourceReference(
                localChanges.last().token.ids.first(),
                "Patient/patient-3",
                "Group.member",
              ),
            )
          }

      whenever(database.getLocalChangeResourceReferences(any()))
        .thenReturn(localChangeResourceReferences)

      val result =
        patchGenerator
          .generateSquashedChangesMapping(localChanges)
          .createAdjacencyListForCreateReferences(
            localChanges.map { "${it.resourceType}/${it.resourceId}" }.toSet(),
            localChangeResourceReferences.groupBy { it.localChangeId },
          )

      assertThat(result)
        .isEqualTo(
          mutableMapOf(
            "Group/group-1" to
              listOf("Patient/patient-1", "Patient/patient-2", "Patient/patient-3"),
            "Patient/patient-1" to emptyList(),
            "Patient/patient-2" to emptyList(),
            "Patient/patient-3" to emptyList(),
            "Encounter/encounter-1" to listOf("Patient/patient-1"),
            "Encounter/encounter-2" to listOf("Patient/patient-2"),
            "Encounter/encounter-3" to listOf("Patient/patient-3"),
            "Observation/observation-1" to listOf("Patient/patient-1", "Encounter/encounter-1"),
            "Observation/observation-2" to listOf("Patient/patient-2", "Encounter/encounter-2"),
            "Observation/observation-3" to listOf("Patient/patient-3", "Encounter/encounter-3"),
          ),
        )
    }

  @Test
  fun `createReferenceAdjacencyList with local changes for new and old resources should only have edges to inserted resources`() =
    runTest {
      val localChanges = mutableListOf<LocalChange>()
      val localChangeResourceReferences = mutableListOf<LocalChangeResourceReference>()

      var group =
        Group()
          .apply {
            id = "group-1"
            type = Group.GroupType.PERSON
          }
          .also { localChanges.add(createInsertLocalChange(it, Random.nextLong())) }

      // pa-01
      Patient()
        .apply { id = "patient-1" }
        .also {
          localChanges.add(
            createUpdateLocalChange(
              it,
              it.copy().apply { active = true },
              Random.nextLong(),
            ),
          )
        }

      Encounter()
        .apply {
          id = "encounter-1"
          subject = Reference("Patient/patient-1")
        }
        .also {
          localChanges.add(createInsertLocalChange(it, Random.nextLong()))
          localChangeResourceReferences.add(
            LocalChangeResourceReference(
              localChanges.last().token.ids.first(),
              "Patient/patient-1",
              "Encounter.subject",
            ),
          )
        }

      Observation()
        .apply {
          id = "observation-1"
          subject = Reference("Patient/patient-1")
          encounter = Reference("Encounter/encounter-1")
        }
        .also {
          localChanges.add(createInsertLocalChange(it, Random.nextLong()))
          localChangeResourceReferences.add(
            LocalChangeResourceReference(
              localChanges.last().token.ids.first(),
              "Patient/patient-1",
              "Observation.subject",
            ),
          )
          localChangeResourceReferences.add(
            LocalChangeResourceReference(
              localChanges.last().token.ids.first(),
              "Encounter/encounter-1",
              "Observation.encounter",
            ),
          )
        }

      group =
        group
          .copy()
          .apply { addMember(Group.GroupMemberComponent(Reference("Patient/patient-1"))) }
          .also {
            localChanges.add(createUpdateLocalChange(group, it, Random.nextLong()))
            localChangeResourceReferences.add(
              LocalChangeResourceReference(
                localChanges.last().token.ids.first(),
                "Patient/patient-1",
                "Group.member",
              ),
            )
          }

      // pa-02
      Patient()
        .apply { id = "patient-2" }
        .also {
          localChanges.add(
            createUpdateLocalChange(
              it,
              it.copy().apply { active = true },
              Random.nextLong(),
            ),
          )
        }
      Encounter()
        .apply {
          id = "encounter-2"
          subject = Reference("Patient/patient-2")
        }
        .also {
          localChanges.add(createInsertLocalChange(it, Random.nextLong()))
          localChangeResourceReferences.add(
            LocalChangeResourceReference(
              localChanges.last().token.ids.first(),
              "Patient/patient-2",
              "Encounter.subject",
            ),
          )
        }

      Observation()
        .apply {
          id = "observation-2"
          subject = Reference("Patient/patient-2")
          encounter = Reference("Encounter/encounter-2")
        }
        .also {
          localChanges.add(createInsertLocalChange(it, Random.nextLong()))
          localChangeResourceReferences.add(
            LocalChangeResourceReference(
              localChanges.last().token.ids.first(),
              "Patient/patient-2",
              "Observation.subject",
            ),
          )
          localChangeResourceReferences.add(
            LocalChangeResourceReference(
              localChanges.last().token.ids.first(),
              "Encounter/encounter-2",
              "Observation.encounter",
            ),
          )
        }

      group =
        group
          .copy()
          .apply { addMember(Group.GroupMemberComponent(Reference("Patient/patient-2"))) }
          .also {
            localChanges.add(createUpdateLocalChange(group, it, Random.nextLong()))
            localChangeResourceReferences.add(
              LocalChangeResourceReference(
                localChanges.last().token.ids.first(),
                "Patient/patient-2",
                "Group.member",
              ),
            )
          }

      // pa-03
      Patient()
        .apply { id = "patient-3" }
        .also {
          localChanges.add(
            createUpdateLocalChange(
              it,
              it.copy().apply { active = true },
              Random.nextLong(),
            ),
          )
        }

      Encounter()
        .apply {
          id = "encounter-3"
          subject = Reference("Patient/patient-3")
        }
        .also {
          localChanges.add(createInsertLocalChange(it, Random.nextLong()))
          localChangeResourceReferences.add(
            LocalChangeResourceReference(
              localChanges.last().token.ids.first(),
              "Patient/patient-3",
              "Encounter.subject",
            ),
          )
        }

      Observation()
        .apply {
          id = "observation-3"
          subject = Reference("Patient/patient-3")
          encounter = Reference("Encounter/encounter-3")
        }
        .also {
          localChanges.add(createInsertLocalChange(it, Random.nextLong()))
          localChangeResourceReferences.add(
            LocalChangeResourceReference(
              localChanges.last().token.ids.first(),
              "Patient/patient-3",
              "Observation.subject",
            ),
          )
          localChangeResourceReferences.add(
            LocalChangeResourceReference(
              localChanges.last().token.ids.first(),
              "Encounter/encounter-3",
              "Observation.encounter",
            ),
          )
        }

      group =
        group
          .copy()
          .apply { addMember(Group.GroupMemberComponent(Reference("Patient/patient-3"))) }
          .also {
            localChanges.add(createUpdateLocalChange(group, it, Random.nextLong()))
            localChangeResourceReferences.add(
              LocalChangeResourceReference(
                localChanges.last().token.ids.first(),
                "Patient/patient-3",
                "Group.member",
              ),
            )
          }

      whenever(database.getLocalChangeResourceReferences(any()))
        .thenReturn(localChangeResourceReferences)

      val result =
        patchGenerator
          .generateSquashedChangesMapping(localChanges)
          .createAdjacencyListForCreateReferences(
            localChanges
              .filter { it.type == LocalChange.Type.INSERT }
              .map { "${it.resourceType}/${it.resourceId}" }
              .toSet(),
            localChangeResourceReferences.groupBy { it.localChangeId },
          )

      assertThat(result)
        .isEqualTo(
          mutableMapOf(
            "Group/group-1" to emptyList(),
            "Patient/patient-1" to emptyList(),
            "Patient/patient-2" to emptyList(),
            "Patient/patient-3" to emptyList(),
            "Encounter/encounter-1" to emptyList(),
            "Encounter/encounter-2" to emptyList(),
            "Encounter/encounter-3" to emptyList(),
            "Observation/observation-1" to listOf("Encounter/encounter-1"),
            "Observation/observation-2" to listOf("Encounter/encounter-2"),
            "Observation/observation-3" to listOf("Encounter/encounter-3"),
          ),
        )
    }

  @Test
  fun `generate with acyclic references should return the list in topological order`() = runTest {
    val localChanges = LinkedList<LocalChange>()
    val localChangeResourceReferences = mutableListOf<LocalChangeResourceReference>()

    var group =
      Group()
        .apply {
          id = "group-1"
          type = Group.GroupType.PERSON
        }
        .also { localChanges.add(createInsertLocalChange(it, Random.nextLong())) }

    // pa-01
    group =
      group
        .copy()
        .apply { addMember(Group.GroupMemberComponent(Reference("Patient/patient-1"))) }
        .also {
          localChanges.add(createUpdateLocalChange(group, it, Random.nextLong()))
          localChangeResourceReferences.add(
            LocalChangeResourceReference(
              localChanges.last().token.ids.first(),
              "Patient/patient-1",
              "Group.member",
            ),
          )
        }

    Observation()
      .apply {
        id = "observation-1"
        subject = Reference("Patient/patient-1")
        encounter = Reference("Encounter/encounter-1")
      }
      .also {
        localChanges.add(createInsertLocalChange(it, Random.nextLong()))
        localChangeResourceReferences.add(
          LocalChangeResourceReference(
            localChanges.last().token.ids.first(),
            "Patient/patient-1",
            "Observation.subject",
          ),
        )
        localChangeResourceReferences.add(
          LocalChangeResourceReference(
            localChanges.last().token.ids.first(),
            "Encounter/encounter-1",
            "Observation.encounter",
          ),
        )
      }

    Encounter()
      .apply {
        id = "encounter-1"
        subject = Reference("Patient/patient-1")
      }
      .also {
        localChanges.add(createInsertLocalChange(it, Random.nextLong()))
        localChangeResourceReferences.add(
          LocalChangeResourceReference(
            localChanges.last().token.ids.first(),
            "Patient/patient-1",
            "Encounter.subject",
          ),
        )
      }

    Patient()
      .apply { id = "patient-1" }
      .also { localChanges.add(createInsertLocalChange(it, Random.nextLong())) }

    // pa-02
    group =
      group
        .copy()
        .apply { addMember(Group.GroupMemberComponent(Reference("Patient/patient-2"))) }
        .also {
          localChanges.add(createUpdateLocalChange(group, it, Random.nextLong()))
          localChangeResourceReferences.add(
            LocalChangeResourceReference(
              localChanges.last().token.ids.first(),
              "Patient/patient-2",
              "Group.member",
            ),
          )
        }

    Observation()
      .apply {
        id = "observation-2"
        subject = Reference("Patient/patient-2")
        encounter = Reference("Encounter/encounter-2")
      }
      .also {
        localChanges.add(createInsertLocalChange(it, Random.nextLong()))
        localChangeResourceReferences.add(
          LocalChangeResourceReference(
            localChanges.last().token.ids.first(),
            "Patient/patient-2",
            "Observation.subject",
          ),
        )
        localChangeResourceReferences.add(
          LocalChangeResourceReference(
            localChanges.last().token.ids.first(),
            "Encounter/encounter-2",
            "Observation.encounter",
          ),
        )
      }

    Encounter()
      .apply {
        id = "encounter-2"
        subject = Reference("Patient/patient-2")
      }
      .also {
        localChanges.add(createInsertLocalChange(it, Random.nextLong()))
        localChangeResourceReferences.add(
          LocalChangeResourceReference(
            localChanges.last().token.ids.first(),
            "Patient/patient-1",
            "Encounter.subject",
          ),
        )
      }

    Patient()
      .apply { id = "patient-2" }
      .also { localChanges.add(createInsertLocalChange(it, Random.nextLong())) }

    // pa-03
    group =
      group
        .copy()
        .apply { addMember(Group.GroupMemberComponent(Reference("Patient/patient-3"))) }
        .also {
          localChanges.add(createUpdateLocalChange(group, it, Random.nextLong()))
          localChangeResourceReferences.add(
            LocalChangeResourceReference(
              localChanges.last().token.ids.first(),
              "Patient/patient-3",
              "Group.member",
            ),
          )
        }

    Observation()
      .apply {
        id = "observation-3"
        subject = Reference("Patient/patient-3")
        encounter = Reference("Encounter/encounter-3")
      }
      .also {
        localChanges.add(createInsertLocalChange(it, Random.nextLong()))
        localChangeResourceReferences.add(
          LocalChangeResourceReference(
            localChanges.last().token.ids.first(),
            "Patient/patient-3",
            "Observation.subject",
          ),
        )
        localChangeResourceReferences.add(
          LocalChangeResourceReference(
            localChanges.last().token.ids.first(),
            "Encounter/encounter-3",
            "Observation.encounter",
          ),
        )
      }

    Encounter()
      .apply {
        id = "encounter-3"
        subject = Reference("Patient/patient-3")
      }
      .also {
        localChanges.add(createInsertLocalChange(it, Random.nextLong()))
        localChangeResourceReferences.add(
          LocalChangeResourceReference(
            localChanges.last().token.ids.first(),
            "Patient/patient-3",
            "Encounter.subject",
          ),
        )
      }

    Patient()
      .apply { id = "patient-3" }
      .also { localChanges.add(createInsertLocalChange(it, Random.nextLong())) }

    whenever(database.getLocalChangeResourceReferences(any()))
      .thenReturn(localChangeResourceReferences)

    val result = patchGenerator.generate(localChanges)
    assertThat(result.map { it.generatedPatch.resourceId })
      .containsExactly(
        "patient-1",
        "patient-2",
        "patient-3",
        "group-1",
        "encounter-1",
        "observation-1",
        "encounter-2",
        "observation-2",
        "encounter-3",
        "observation-3",
      )
      .inOrder()
  }

  @Test
  fun `generate with cyclic references should throw exception`() = runTest {
    val localChanges = LinkedList<LocalChange>()
    val localChangeResourceReferences = mutableListOf<LocalChangeResourceReference>()

    Patient()
      .apply {
        id = "patient-1"
        addLink(
          Patient.PatientLinkComponent().apply { other = Reference("RelatedPerson/related-1") },
        )
      }
      .also {
        localChanges.add(createInsertLocalChange(it, Random.nextLong()))
        localChangeResourceReferences.add(
          LocalChangeResourceReference(
            localChanges.last().token.ids.first(),
            "RelatedPerson/related-1",
            "Patient.other",
          ),
        )
      }

    RelatedPerson()
      .apply {
        id = "related-1"
        patient = Reference("Patient/patient-1")
      }
      .also {
        localChanges.add(createInsertLocalChange(it))
        localChangeResourceReferences.add(
          LocalChangeResourceReference(
            localChanges.last().token.ids.first(),
            "Patient/patient-1",
            "RelatedPerson.patient",
          ),
        )
      }

    whenever(database.getLocalChangeResourceReferences(any()))
      .thenReturn(localChangeResourceReferences)

    val errorMessage =
      assertFailsWith<IllegalStateException> { patchGenerator.generate(localChanges) }
        .localizedMessage

    assertThat(errorMessage).isEqualTo("Detected a cycle.")
  }
}
