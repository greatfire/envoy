package com.example.myapplication

class Secrets {

    // Method calls will be added by gradle task hideSecret
    // Example : external fun getWellHiddenSecret(packageName: String): String

    companion object {
        init {
            System.loadLibrary("secrets")
        }
    }

    external fun gethystCert(packageName: String): String

    external fun getdefProxy(packageName: String): String
}