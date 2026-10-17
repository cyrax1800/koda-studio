/*
 * Copyright 2026  Koda Studio contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

@file:OptIn(KaPlatformInterface::class)
package org.kotlinlsp.analysis.services

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.lifetime.KaLifetimeToken
import org.jetbrains.kotlin.analysis.api.platform.lifetime.KotlinLifetimeTokenFactory
import org.jetbrains.kotlin.analysis.api.platform.lifetime.ModificationTrackerWithInvalidationReason
import kotlin.reflect.KClass

/**
 * A lifetime token that is always accessible (the server guards analysis with its own read/write lock rather than IntelliJ
 * read actions) and stays valid exactly as long as its analysis session does.
 *
 * `KotlinAlwaysAccessibleLifetimeToken` instead turns invalid on *any* source change in the project, while the engine
 * rightly keeps the cached sessions of modules an edit doesn't affect. After typing in core:data, the next request in
 * core:datastore got its cached session back with a dead token: "An analysis session acquired via `getAnalysisSession`
 * must be valid".
 */
class LspLifetimeToken(private val validityTracker: ModificationTracker) : KaLifetimeToken() {
    private val createdAt = validityTracker.modificationCount

    override fun isValid(): Boolean = validityTracker.modificationCount == createdAt

    override fun getInvalidationReason(): String =
        (validityTracker as? ModificationTrackerWithInvalidationReason)?.getInvalidationReason()
            ?: "The analysis session was invalidated"

    override fun isAccessible(): Boolean = true

    override fun getInaccessibilityReason(): String = error("The token is always accessible")
}

class LspLifetimeTokenFactory : KotlinLifetimeTokenFactory {
    override val identifier: KClass<out KaLifetimeToken> = LspLifetimeToken::class

    override fun create(project: Project, modificationTracker: ModificationTracker): KaLifetimeToken =
        LspLifetimeToken(modificationTracker)
}
