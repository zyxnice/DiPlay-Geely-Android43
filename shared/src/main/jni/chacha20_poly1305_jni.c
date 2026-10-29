/*
 * Media-only JNI adapter to the GPL-3.0 ChaCha implementation adapted from
 * DiPlay-Legacy-Android bec397e2. JNI methods used here exist on API 18.
 * Authentication is checked before output is written. Input ownership stays
 * with Kotlin; this adapter never rewrites the caller's ciphertext.
 */
#include <jni.h>
#include "chacha20_poly1305.h"

#define MAX_AAD_SIZE 1024
#define RESULT_AUTH_FAILED (-1)
#define RESULT_UNAVAILABLE (-2)

JNIEXPORT jint JNICALL
Java_com_shilapi_xcertplay_airplay_NativeChaCha_nativeOpen(
        JNIEnv *env, jobject self, jbyteArray key_array, jbyteArray nonce_array,
        jbyteArray aad_array, jbyteArray sealed_array, jbyteArray output_array) {
    unsigned char key[CPP_KEY_SIZE];
    unsigned char nonce[CPP_NONCE_SIZE];
    unsigned char aad[MAX_AAD_SIZE];
    jsize sealed_length;
    jsize plaintext_length;
    jsize aad_length;
    jbyte *sealed;
    jbyte *output;
    int result;
    (void)self;

    if (key_array == NULL || nonce_array == NULL || aad_array == NULL ||
            sealed_array == NULL || output_array == NULL) return RESULT_UNAVAILABLE;
    if ((*env)->GetArrayLength(env, key_array) != CPP_KEY_SIZE ||
            (*env)->GetArrayLength(env, nonce_array) != CPP_NONCE_SIZE) return RESULT_UNAVAILABLE;
    sealed_length = (*env)->GetArrayLength(env, sealed_array);
    if (sealed_length < CPP_TAG_SIZE) return RESULT_UNAVAILABLE;
    plaintext_length = sealed_length - CPP_TAG_SIZE;
    if ((*env)->GetArrayLength(env, output_array) != plaintext_length) return RESULT_UNAVAILABLE;
    if ((*env)->IsSameObject(env, sealed_array, output_array) ||
            (*env)->IsSameObject(env, key_array, output_array) ||
            (*env)->IsSameObject(env, nonce_array, output_array) ||
            (*env)->IsSameObject(env, aad_array, output_array)) return RESULT_UNAVAILABLE;
    aad_length = (*env)->GetArrayLength(env, aad_array);
    if (aad_length > MAX_AAD_SIZE) return RESULT_UNAVAILABLE;

    (*env)->GetByteArrayRegion(env, key_array, 0, CPP_KEY_SIZE, (jbyte *)key);
    if ((*env)->ExceptionCheck(env)) return RESULT_UNAVAILABLE;
    (*env)->GetByteArrayRegion(env, nonce_array, 0, CPP_NONCE_SIZE, (jbyte *)nonce);
    if ((*env)->ExceptionCheck(env)) return RESULT_UNAVAILABLE;
    if (aad_length > 0) (*env)->GetByteArrayRegion(env, aad_array, 0, aad_length, (jbyte *)aad);
    if ((*env)->ExceptionCheck(env)) return RESULT_UNAVAILABLE;

    /* No PrimitiveArrayCritical section: a large frame must not prevent GC.
     * Dalvik may pin or copy these arrays; this is not a zero-copy guarantee. */
    sealed = (*env)->GetByteArrayElements(env, sealed_array, NULL);
    if (sealed == NULL) return RESULT_UNAVAILABLE;
    output = (*env)->GetByteArrayElements(env, output_array, NULL);
    if (output == NULL) {
        (*env)->ReleaseByteArrayElements(env, sealed_array, sealed, JNI_ABORT);
        return RESULT_UNAVAILABLE;
    }
    result = cpp_aead_open_into(key, nonce, aad, (size_t)aad_length,
        (const unsigned char *)sealed, (size_t)plaintext_length, (unsigned char *)output);
    (*env)->ReleaseByteArrayElements(env, output_array, output, result == 0 ? 0 : JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, sealed_array, sealed, JNI_ABORT);
    return result == 0 ? plaintext_length : RESULT_AUTH_FAILED;
}
