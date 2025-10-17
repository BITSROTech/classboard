package co.kys.classboard.proto

import kotlinx.serialization.Serializable

/** 교사가 특정 사용자에 대해 판서 권한을 부여/회수 */
@Serializable
data class DrawPermit(
    val userId: String,
    val allowed: Boolean
)
