package com.pocketscope.network

import org.junit.Assert.*
import org.junit.Test

class NetworkFilterTest {

    // isPrivateIp tests

    @Test
    fun `192_168 range is private`() {
        assertTrue(NetworkFilter.isPrivateIp("192.168.1.10"))
    }

    @Test
    fun `10_x range is private`() {
        assertTrue(NetworkFilter.isPrivateIp("10.0.0.1"))
    }

    @Test
    fun `172_16 range start is private`() {
        assertTrue(NetworkFilter.isPrivateIp("172.16.0.1"))
    }

    @Test
    fun `172_31 range end is private`() {
        assertTrue(NetworkFilter.isPrivateIp("172.31.255.255"))
    }

    @Test
    fun `loopback is private`() {
        assertTrue(NetworkFilter.isPrivateIp("127.0.0.1"))
    }

    @Test
    fun `google dns is not private`() {
        assertFalse(NetworkFilter.isPrivateIp("8.8.8.8"))
    }

    @Test
    fun `172_32 is not private`() {
        assertFalse(NetworkFilter.isPrivateIp("172.32.0.1"))
    }

    @Test
    fun `arbitrary public ip is not private`() {
        assertFalse(NetworkFilter.isPrivateIp("1.2.3.4"))
    }

    // extractIp tests

    @Test
    fun `extractIp strips leading slash and port`() {
        assertEquals("192.168.1.5", NetworkFilter.extractIp("/192.168.1.5:54321"))
    }

    @Test
    fun `extractIp handles bare ip`() {
        assertEquals("192.168.1.5", NetworkFilter.extractIp("192.168.1.5"))
    }
}
