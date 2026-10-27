#include <jni.h>
#include <errno.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <linux/usbdevice_fs.h>
#include "geely_usb_configuration.h"

// Separate opt-in entry: the existing configure() contract and other platforms are unchanged.
JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_compat_LegacyUsbNative_configureGeely(JNIEnv *env, jobject self, jint fd, jint id) {
    (void) env; (void) self;
    if (fd < 0) return -EBADF;
    int owned_fd = dup(fd);
    if (owned_fd < 0) return -errno;
    int result = geely_configure_fd(owned_fd, (unsigned int) id);
    close(owned_fd);
    return result;
}

// Operate only on an already-authorized UsbDeviceConnection fd; never open/close it.
JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_compat_LegacyUsbNative_configure(JNIEnv *env, jobject self, jint fd, jint id) {
    (void) env; (void) self;
    if (fd < 0) return -EBADF;
    if (id < 1 || id > 255) return -EINVAL;
    unsigned int config = (unsigned int) id;
    int owned_fd = dup(fd);
    if (owned_fd < 0) return -errno;
    int result = ioctl(owned_fd, USBDEVFS_SETCONFIGURATION, &config) == 0 ? 0 : -errno;
    close(owned_fd);
    return result;
}
JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_compat_LegacyUsbNative_selectAlternate(JNIEnv *env, jobject self, jint fd, jint id, jint alt) {
    (void) env; (void) self;
    if (fd < 0) return -EBADF;
    if (id < 0 || id > 255 || alt < 0 || alt > 255) return -EINVAL;
    struct usbdevfs_setinterface request;
    memset(&request, 0, sizeof(request));
    request.interface = (unsigned int) id;
    request.altsetting = (unsigned int) alt;
    int owned_fd = dup(fd);
    if (owned_fd < 0) return -errno;
    int result = ioctl(owned_fd, USBDEVFS_SETINTERFACE, &request) == 0 ? 0 : -errno;
    close(owned_fd);
    return result;
}
JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_compat_LegacyUsbNative_claim(JNIEnv *env, jobject self, jint fd, jint id) {
    (void) env; (void) self;
    if (fd < 0) return -EBADF;
    if (id < 0 || id > 255) return -EINVAL;
    unsigned int number = (unsigned int) id;
    int owned_fd = dup(fd);
    if (owned_fd < 0) return -errno;
    int result = ioctl(owned_fd, USBDEVFS_CLAIMINTERFACE, &number) == 0 ? 0 : -errno;
    close(owned_fd);
    return result;
}
