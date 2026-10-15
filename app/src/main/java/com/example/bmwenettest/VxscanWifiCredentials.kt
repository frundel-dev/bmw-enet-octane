package com.example.bmwenettest

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Opt-in auto-connect profile. The Wi-Fi passphrase is NEVER stored in
 * plaintext preferences, logs, saved state or Intent extras.
 * If AndroidKeyStore is unavailable, fail closed and require manual entry.
 */
internal object VxscanWifiCredentials {
    data class Profile(val ssid:String,val password:String,val bssid:String?)
    private const val KEY_ALIAS="bmw_octane_vxscan_local_wifi_v1"
    private const val PREFS="bmw_native"
    private const val ENABLED="vxscan_local_only_auto_enabled"
    private const val SSID="vxscan_local_only_saved_ssid"
    private const val PASSWORD="vxscan_local_only_secret_gcm"
    private const val BSSID="vxscan_local_only_saved_bssid"

    private fun secretKey():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing=store.getKey(KEY_ALIAS,null) as? SecretKey
        if(existing!=null)return existing
        val generator=KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    private fun encrypt(password:String):String {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,secretKey())
        val ciphertext=cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv+ciphertext,Base64.NO_WRAP)
    }
    private fun decrypt(value:String):String {
        val blob=Base64.decode(value,Base64.NO_WRAP)
        require(blob.size>=12+16)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,secretKey(),GCMParameterSpec(
            128,blob.copyOfRange(0,12)))
        return cipher.doFinal(blob.copyOfRange(12,blob.size))
            .toString(Charsets.UTF_8)
    }
    fun enabled(context:Context):Boolean=context.getSharedPreferences(
        PREFS,Context.MODE_PRIVATE).getBoolean(ENABLED,false)

    /** Atomic storage: encryption failure never persists an enabled profile. */
    fun save(context:Context,ssid:String,password:String,bssid:String?):Boolean {
        if(VxscanLocalOnlyPolicy.validate(ssid,password)!=null ||
            VxscanLocalOnlyPolicy.validateBssid(bssid)!=null)return false
        val secret=try { encrypt(password) }catch(_:Exception){return false}
        return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
            .putString(SSID,ssid)
            .putString(PASSWORD,secret)
            .putString(BSSID,bssid.orEmpty())
            .putBoolean(ENABLED,true)
            .commit()
    }

    fun load(context:Context):Profile? {
        val prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        if(!prefs.getBoolean(ENABLED,false))return null
        val ssid=prefs.getString(SSID,null) ?: return null
        val secret=prefs.getString(PASSWORD,null) ?: return null
        val bssid=prefs.getString(BSSID,null)?.ifBlank { null }
        val password=try { decrypt(secret) }catch(_:Exception){return null}
        if(VxscanLocalOnlyPolicy.validate(ssid,password)!=null ||
            VxscanLocalOnlyPolicy.validateBssid(bssid)!=null)return null
        return Profile(ssid,password,bssid)
    }

    fun lastSsid(context:Context):String?=context.getSharedPreferences(
        PREFS,Context.MODE_PRIVATE).getString(SSID,null)

    fun lastBssid(context:Context):String?=context.getSharedPreferences(
        PREFS,Context.MODE_PRIVATE).getString(BSSID,null)?.ifBlank { null }

    /** Captured from the approved network, never from an arbitrary scan. */
    fun rememberBssid(context:Context,ssid:String,bssid:String) {
        if(VxscanLocalOnlyPolicy.validateBssid(bssid)!=null)return
        val prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        if(prefs.getBoolean(ENABLED,false) && prefs.getString(SSID,null)==ssid)
            prefs.edit().putString(BSSID,bssid).apply()
    }

    /** Explicit opt-out: forget all profile fields, encrypted password included. */
    fun clear(context:Context) {
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
            .remove(ENABLED).remove(SSID).remove(PASSWORD).remove(BSSID).commit()
        // Keystore material belongs exclusively to this feature.
        try {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(KEY_ALIAS)
            }
        }catch(_:Exception) {}
    }
}
