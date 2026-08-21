package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Real on-device persistence for interception.
 *
 * Ships with NO seed data by design: a fresh install has an empty traffic log, so the UI
 * renders a genuine empty state instead of the fabricated sample rows that shipped in v2.1.
 */
@Database(
    entities = [
        CapturedTrafficEntity::class,
        CaptureSessionEntity::class,
        InterceptRuleEntity::class,
        SavedApiRequestEntity::class,
        FlowNodeEntity::class,
        FlowConnectionEntity::class,
        TcpConnectionEntity::class,
        UdpFlowEntity::class,
        QuicConnectionEntity::class,
        TlsSessionEntity::class,
        DnsQueryEntity::class,
    ],
    // v3.5: bumped 4 -> 5 for FlowNodeEntity + FlowConnectionEntity (Flow persistence).
    // v5.6 (M1): bumped 5 -> 6 to register TcpConnectionEntity/UdpFlowEntity/QuicConnectionEntity,
    // which previously existed as dead code with no table backing them.
    // v5.6 (M2): bumped 6 -> 7 to register TlsSessionEntity (TLS handshake/cipher/cert model).
    // v5.6 (T2): bumped 7 -> 8 to add CapturedTrafficEntity.sourceIp + CaptureTier.GATEWAY,
    // used by GatewayEngine to tag/group traffic proxied on behalf of remote LAN devices.
    // v5.7 (D2): also part of the 7 -> 8 bump (merged in the same release): registers
    // DnsQueryEntity -- PacketPump.dnsCallback previously parsed real DNS queries/responses via
    // DnsParser and discarded them (log-only); this table is what makes DNS a genuine first hop
    // in ConnectionLineage instead of a documented gap.
    version = 8,
    exportSchema = false,
)
@TypeConverters(RequestrTypeConverters::class)
abstract class RequestrDatabase : RoomDatabase() {

    abstract fun capturedTrafficDao(): CapturedTrafficDao
    abstract fun captureSessionDao(): CaptureSessionDao
    abstract fun interceptRuleDao(): InterceptRuleDao
    abstract fun savedApiRequestDao(): SavedApiRequestDao
    abstract fun flowNodeDao(): FlowNodeDao
    abstract fun flowConnectionDao(): FlowConnectionDao
    abstract fun tcpConnectionDao(): TcpConnectionDao
    abstract fun udpFlowDao(): UdpFlowDao
    abstract fun quicConnectionDao(): QuicConnectionDao
    abstract fun tlsSessionDao(): TlsSessionDao
    abstract fun dnsQueryDao(): DnsQueryDao

    companion object {
        @Volatile
        private var INSTANCE: RequestrDatabase? = null

        fun get(context: Context): RequestrDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    RequestrDatabase::class.java,
                    "requestr.db",
                )
                    // Schema is young; destructive migration is acceptable pre-1.0 and avoids
                    // shipping a broken upgrade path. Replace with real migrations post-release.
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
