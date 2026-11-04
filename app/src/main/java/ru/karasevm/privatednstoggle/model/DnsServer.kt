package ru.karasevm.privatednstoggle.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// All fields must have default values for proper deserialization
@Serializable
// Addresses are unique, see MIGRATION_1_2
@Entity(tableName = "dns_servers", indices = [Index(value = ["server"], unique = true)])
data class DnsServer(
    @SerialName("id")
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @SerialName("server")
    val server: String = "",
    @SerialName("label")
    val label: String = "",
    @SerialName("enabled")
    @ColumnInfo(defaultValue = "1")
    val enabled: Boolean = true,
    val sortOrder: Int? = null
)
