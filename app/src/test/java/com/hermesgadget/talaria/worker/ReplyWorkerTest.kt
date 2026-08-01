/*
 * Copyright 2026 Talaria contributors
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

package com.hermesgadget.talaria.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplyWorkerTest {
    @Test
    fun extractsSessionTargetFromNotificationDeepLink() {
        assertEquals("session-123", ReplyWorker.sessionIdFromDeepLink("talaria://session/session-123"))
        assertEquals("session id+1", ReplyWorker.sessionIdFromDeepLink("talaria://session/session%20id%2B1"))
    }

    @Test
    fun rejectsUnrelatedOrMalformedLinks() {
        assertNull(ReplyWorker.sessionIdFromDeepLink("talaria://chat"))
        assertNull(ReplyWorker.sessionIdFromDeepLink("https://example.test/session/secret"))
        assertNull(ReplyWorker.sessionIdFromDeepLink("not a uri"))
        assertNull(ReplyWorker.sessionIdFromDeepLink("talaria://session/one/two"))
    }
}
