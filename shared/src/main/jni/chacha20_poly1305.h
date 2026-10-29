/* Adapted from programmerguohuajing/DiPlay-Legacy-Android, commit
 * bec397e2a3bbf85a4ff3128be68dd2fcc810d18f; GPL-3.0, see repository LICENSE.
 * Local change: separate-output authenticated open preserves caller ciphertext.
 */
#ifndef XCERTPLAY_CHACHA20_POLY1305_H
#define XCERTPLAY_CHACHA20_POLY1305_H

#include <stddef.h>
#include <stdint.h>

#define CPP_KEY_SIZE 32
#define CPP_NONCE_SIZE 12
#define CPP_TAG_SIZE 16

/*
 * ChaCha20-Poly1305 AEAD (RFC 8439), used for every CarPlay video frame.
 *
 * This is a portable C path for legacy Dalvik devices. Its throughput on the
 * target head unit must be measured; no frame-rate guarantee is implied.
 *
 * Nothing here touches JNI, so shared/src/test/native can host-test the exact
 * code that ships in the APK.
 */

/* Seals [plaintext] into [out], which must hold plaintext_len + CPP_TAG_SIZE bytes. */
int cpp_aead_seal(const unsigned char key[CPP_KEY_SIZE],
                  const unsigned char nonce[CPP_NONCE_SIZE],
                  const unsigned char *aad, size_t aad_len,
                  const unsigned char *plaintext, size_t plaintext_len,
                  unsigned char *out);

/*
 * Opens [buf] in place; it holds ciphertext_len bytes followed by the 16-byte tag.
 * Returns 0 on success, or -1 when the tag does not match (nothing was decrypted).
 */
int cpp_aead_open(const unsigned char key[CPP_KEY_SIZE],
                  const unsigned char nonce[CPP_NONCE_SIZE],
                  const unsigned char *aad, size_t aad_len,
                  unsigned char *buf, size_t ciphertext_len);

/* Opens into a separate output of ciphertext_len bytes. Both buffers stay
 * untouched on authentication failure; ciphertext_and_tag is never modified.
 * The caller validates buffer lengths before entering this primitive.
 */
int cpp_aead_open_into(const unsigned char key[CPP_KEY_SIZE],
                      const unsigned char nonce[CPP_NONCE_SIZE],
                      const unsigned char *aad, size_t aad_len,
                      const unsigned char *ciphertext_and_tag, size_t ciphertext_len,
                      unsigned char *output);

#endif
