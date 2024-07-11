package com.example.myapplication

class Secrets {

    // Method calls will be added by gradle task hideSecret
    // Example : external fun getWellHiddenSecret(packageName: String): String

    companion object {
        init {
            System.loadLibrary("secrets")
        }
    }

    external fun getmeekUrl(packageName: String): String

    external fun getsnowflakeUrl(packageName: String): String

    external fun getv2srtpUrl(packageName: String): String

    external fun getv2wechatUrl(packageName: String): String

    external fun getenvoyUrl(packageName: String): String

    external fun getshadowsocksUrl(packageName: String): String
}