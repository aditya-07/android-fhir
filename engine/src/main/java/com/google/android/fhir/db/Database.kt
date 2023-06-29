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

package com.google.android.fhir.db

import com.google.android.fhir.db.impl.dao.IndexedIdAndResource
import com.google.android.fhir.db.impl.dao.LocalChangeToken
import com.google.android.fhir.db.impl.dao.SquashedLocalChange
import com.google.android.fhir.db.impl.entities.LocalChangeEntity
import com.google.android.fhir.db.impl.entities.ResourceEntity
import com.google.android.fhir.search.SearchQuery
import java.time.Instant
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.ResourceType

/** The interface for the FHIR resource database. */
internal interface Database {
  /**
   * Inserts a list of local `resources` into the FHIR resource database. If any of the resources
   * already exists, it will be overwritten.
   *
   * @param <R> The resource type
   * @return the logical IDs of the newly created resources.
   */
  suspend fun <R : Resource> insert(vararg resource: R): List<String>

  /**
   * Inserts a list of remote `resources` into the FHIR resource database. If any of the resources
   * already exists, it will be overwritten.
   *
   * @param <R> The resource type
   */
  suspend fun <R : Resource> insertRemote(vararg resource: R)

  /**
   * Updates the `resource` in the FHIR resource database. If the resource does not already exist,
   * then it will not be created.
   *
   * @param <R> The resource type
   */
  suspend fun update(vararg resources: Resource)

  /** Updates the `resource` meta in the FHIR resource database. */
  suspend fun updateVersionIdAndLastUpdated(
    resourceId: String,
    resourceType: ResourceType,
    versionId: String,
    lastUpdated: Instant
  )

  /**
   * Selects the FHIR resource of type `clazz` with `id`.
   *
   * @param <R> The resource type
   * @throws ResourceNotFoundException if the resource is not found in the database
   */
  @Throws(ResourceNotFoundException::class)
  suspend fun select(type: ResourceType, id: String): Resource

  /**
   * Selects the saved `ResourceEntity` of type `clazz` with `id`.
   *
   * @param <R> The resource type
   * @throws ResourceNotFoundException if the resource is not found in the database
   */
  @Throws(ResourceNotFoundException::class)
  suspend fun selectEntity(type: ResourceType, id: String): ResourceEntity

  /**
   * Insert resources that were synchronised.
   *
   * @param syncedResources The synced resource
   */
  suspend fun insertSyncedResources(resources: List<Resource>)

  /**
   * Deletes the FHIR resource of type `clazz` with `id`.
   *
   * @param <R> The resource type
   */
  suspend fun delete(type: ResourceType, id: String)

  suspend fun <R : Resource> search(query: SearchQuery): List<R>

  suspend fun searchReferencedResources(query: SearchQuery): List<IndexedIdAndResource>

  suspend fun count(query: SearchQuery): Long

  /**
   * Retrieves all [LocalChangeEntity] s for all [Resource] s, which can be used to update the
   * remote FHIR server. Each resource will have at most one
   * [LocalChangeEntity](multiple changes are squashed).
   */
  suspend fun getAllLocalChanges(): List<SquashedLocalChange>

  /** Remove the [LocalChangeEntity] s with given ids. Call this after a successful sync. */
  suspend fun deleteUpdates(token: LocalChangeToken)

  /** Remove the [LocalChangeEntity] s with matching resource ids. */
  suspend fun deleteUpdates(resources: List<Resource>)

  /** Runs the block as a database transaction. */
  suspend fun withTransaction(block: suspend () -> Unit)

  /** Closes the database connection. */
  fun close()

  /**
   * Clears all database tables without resetting the auto-increment value generated by
   * PrimaryKey.autoGenerate. WARNING: This will clear the database and it's not recoverable.
   */
  suspend fun clearDatabase()

  /**
   * Retrieve [LocalChangeEntity] for [Resource] with given type and id, which can be used to purge
   * resource from database. Each resource will have at most one
   * [LocalChangeEntity](multiple
   * changes are squashed). If there is no local change for given
   * [resourceType] and [Resource.id], return `null`.
   * @param type The [ResourceType]
   * @param id The resource id [Resource.id]
   * @return [LocalChangeEntity] A squashed local changes for given [resourceType] and [Resource.id]
   * . If there is no local change for given [resourceType] and [Resource.id], return `null`.
   */
  suspend fun getLocalChange(type: ResourceType, id: String): SquashedLocalChange?

  /**
   * Purge resource from database based on resource type and id without any deletion of data from
   * the server.
   * @param type The [ResourceType]
   * @param id The resource id [Resource.id]
   * @param isLocalPurge default value is false here resource will not be deleted from
   * LocalChangeEntity table but it will throw IllegalStateException("Resource has local changes
   * either sync with server or FORCE_PURGE required") if local change exists. If true this API will
   * delete resource entry from LocalChangeEntity table.
   */
  suspend fun purge(type: ResourceType, id: String, forcePurge: Boolean = false)
}
