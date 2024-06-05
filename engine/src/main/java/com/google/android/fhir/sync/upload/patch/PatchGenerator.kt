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

import com.google.android.fhir.LocalChange
import com.google.android.fhir.db.Database

/**
 * Generates [Patch]es from [LocalChange]s and output [List<[PatchMappingGroup]>] to keep a mapping
 * of the [LocalChange]s to their corresponding generated [Patch]
 *
 * INTERNAL ONLY. This interface should NEVER been exposed as an external API because it works
 * together with other components in the upload package to fulfill a specific upload strategy.
 * Application-specific implementations of this interface are unlikely to catch all the edge cases
 * and work with other components in the upload package seamlessly. Should there be a genuine need
 * to control the [Patch]es to be uploaded to the server, more granulated control mechanisms should
 * be opened up to applications to guarantee correctness.
 */
internal interface PatchGenerator {
  /**
   * NOTE: different implementations may have requirements on the size of [localChanges] and output
   * certain numbers of [Patch]es.
   */
  suspend fun generate(localChanges: List<LocalChange>): List<PatchMappingGroup>
}

internal object PatchGeneratorFactory {
  fun byMode(
    mode: PatchGeneratorMode,
    database: Database,
  ): PatchGenerator =
    when (mode) {
      is PatchGeneratorMode.PerChange -> PerChangePatchGenerator
      is PatchGeneratorMode.PerResource -> PerResourcePatchGenerator.with(database)
    }
}

/**
 * Mode to decide the type of [PatchGenerator] that needs to be used to upload the [LocalChange]s
 */
internal sealed class PatchGeneratorMode {
  object PerResource : PatchGeneratorMode()

  object PerChange : PatchGeneratorMode()
}

/**
 * Structure to maintain the mapping between [List<[LocalChange]>] and the [Patch] generated from
 * those changes. This class should be used by any implementation of [PatchGenerator] to output the
 * [Patch] in this format.
 */
internal data class PatchMapping(
  val localChanges: List<LocalChange>,
  val generatedPatch: Patch,
)

/** Structure to help describe the cyclic nature of ordered [PatchMapping]. */
internal sealed interface PatchMappingGroup {

  /**
   * [IndividualMappingGroup] doesn't have a cyclic dependency on other [IndividualMappingGroup] /
   * [PatchMapping]. It may however have an acyclic dependency on other [IndividualMappingGroup]s /
   * [PatchMapping]s.
   */
  data class IndividualMappingGroup(val patchMapping: PatchMapping) : PatchMappingGroup

  /**
   * [CombinedMappingGroup] contains strongly connected [PatchMapping]s where one or more
   * [PatchMapping]s have a cyclic dependency between each other.
   */
  data class CombinedMappingGroup(val patchMappings: List<PatchMapping>) : PatchMappingGroup
}
