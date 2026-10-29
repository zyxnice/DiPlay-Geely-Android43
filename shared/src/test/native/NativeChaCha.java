package com.shilapi.xcertplay.airplay;

import java.util.Arrays;

/**
 * Small app_process/host harness for the actual JNI binary. Compile as Java 7,
 * dex with min-api 18, and pass the absolute .so path as its only argument.
 * Its class and native signature intentionally match the production Kotlin object.
 */
public final class NativeChaCha {
    private native int nativeOpen(byte[] key, byte[] nonce, byte[] aad, byte[] sealed, byte[] output);

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected absolute native library path");
        System.load(args[0]);
        NativeChaCha crypto = new NativeChaCha();
        byte[] key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f");
        byte[] nonce = hex("070000004041424344454647");
        byte[] aad = hex("50515253c0c1c2c3c4c5c6c7");
        byte[] sealed = hex(
                "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6" +
                "3dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b36" +
                "92ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc" +
                "3ff4def08e4b7a9de576d26586cec64b6116" +
                "1ae10b594f09e26a7e902ecbd0600691");
        byte[] expected = ("Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, " +
                "sunscreen would be it.").getBytes("US-ASCII");
        byte[] original = sealed.clone();
        byte[] output = new byte[expected.length];
        check(crypto.nativeOpen(key, nonce, aad, sealed, output) == expected.length, "RFC open length");
        check(Arrays.equals(expected, output), "RFC plaintext");
        check(Arrays.equals(original, sealed), "source preserved");

        sealed[sealed.length - 1] ^= 1;
        byte[] rejected = sealed.clone();
        Arrays.fill(output, (byte) 0x55);
        check(crypto.nativeOpen(key, nonce, aad, sealed, output) == -1, "changed tag rejected");
        check(Arrays.equals(rejected, sealed), "rejected source preserved");
        for (byte value : output) check(value == (byte) 0x55, "rejected output preserved");
        sealed[sealed.length - 1] ^= 1;

        key[0] ^= 1;
        check(crypto.nativeOpen(key, nonce, aad, sealed, output) == -1, "wrong key rejected");
        key[0] ^= 1;
        nonce[0] ^= 1;
        check(crypto.nativeOpen(key, nonce, aad, sealed, output) == -1, "wrong nonce rejected");
        nonce[0] ^= 1;
        aad[0] ^= 1;
        check(crypto.nativeOpen(key, nonce, aad, sealed, output) == -1, "wrong AAD rejected");
        aad[0] ^= 1;
        check(crypto.nativeOpen(new byte[31], nonce, aad, sealed, output) == -2, "invalid key length rejected");
        check(crypto.nativeOpen(key, new byte[11], aad, sealed, output) == -2, "invalid nonce length rejected");
        check(crypto.nativeOpen(key, nonce, aad, new byte[15], output) == -2, "truncated tag rejected");
        check(crypto.nativeOpen(key, nonce, new byte[1025], sealed, output) == -2, "oversized AAD rejected");
        check(crypto.nativeOpen(key, nonce, aad, sealed, new byte[1]) == -2, "invalid output length rejected");
        check(crypto.nativeOpen(key, nonce, aad, sealed, output) == expected.length, "valid frame after rejections");
        check(Arrays.equals(original, sealed), "final source preserved");
        System.out.println("NativeChaCha JNI PASS: RFC8439, authentication, input ownership and bounds");
    }

    private static void check(boolean success, String message) {
        if (!success) throw new AssertionError(message);
    }

    private static byte[] hex(String text) {
        byte[] bytes = new byte[text.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
        }
        return bytes;
    }
}
