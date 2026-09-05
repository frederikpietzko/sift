package org.sift.agents.review

import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.SpringApplication
import org.springframework.context.support.GenericApplicationContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MainTest {
    @BeforeTest
    fun setUp() {
        mockkConstructor(SpringApplication::class)
    }

    @AfterTest
    fun tearDown() {
        unmockkConstructor(SpringApplication::class)
    }

    @Test
    fun `main closes the context and destroys managed resources after runners complete`() {
        val context = GenericApplicationContext()
        var resourceDestroyed = false
        context.defaultListableBeanFactory.registerDisposableBean("resource", DisposableBean { resourceDestroyed = true })
        context.refresh()
        every { anyConstructed<SpringApplication>().run("--spring.profiles.active=local") } returns context

        try {
            main(arrayOf("--spring.profiles.active=local"))

            assertFalse(context.isActive)
            assertTrue(resourceDestroyed)
        } finally {
            context.close()
        }
    }

    @Test
    fun `main propagates startup or runner failure for a nonzero process exit`() {
        val failure = IllegalStateException("review failed")
        every { anyConstructed<SpringApplication>().run() } throws failure

        assertSame(failure, assertFailsWith<IllegalStateException> { main(emptyArray()) })
    }
}