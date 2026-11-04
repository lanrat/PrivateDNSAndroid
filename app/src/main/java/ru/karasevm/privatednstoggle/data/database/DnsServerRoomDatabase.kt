package ru.karasevm.privatednstoggle.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import ru.karasevm.privatednstoggle.data.DnsServerDao
import ru.karasevm.privatednstoggle.model.DnsServer

@Database(entities = [DnsServer::class], version = 2, exportSchema = true)
abstract class DnsServerRoomDatabase : RoomDatabase() {

    abstract fun dnsServerDao(): DnsServerDao

    companion object {
        @Volatile
        private var INSTANCE: DnsServerRoomDatabase? = null
        fun getDatabase(context: Context): DnsServerRoomDatabase {
            val tempInstance = INSTANCE
            if (tempInstance != null) {
                return tempInstance
            }
            synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DnsServerRoomDatabase::class.java,
                    "dns_server_database"
                ).addMigrations(MIGRATION_1_2).build()
                INSTANCE = instance
                return instance
            }
        }
    }
}