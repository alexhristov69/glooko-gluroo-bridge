package com.glookogluroo.bridge.cloud

import com.glookogluroo.bridge.BuildConfig

object CloudConfig {
    val enabled: Boolean
        get() = BuildConfig.USE_CLOUD_SYNC &&
            BuildConfig.API_BASE_URL.isNotBlank() &&
            BuildConfig.COGNITO_CLIENT_ID.isNotBlank()

    val apiBaseUrl: String get() = BuildConfig.API_BASE_URL.trimEnd('/')
    val cognitoRegion: String get() = BuildConfig.COGNITO_REGION
    val cognitoClientId: String get() = BuildConfig.COGNITO_CLIENT_ID

    val cognitoEndpoint: String
        get() = "https://cognito-idp.$cognitoRegion.amazonaws.com/"
}
