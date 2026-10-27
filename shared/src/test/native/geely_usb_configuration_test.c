/* Host test of the production policy, with synthetic usbfs structures/requests and no device I/O. */
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
#ifndef ENODATA
#define ENODATA 61
#endif
enum { USBDEVFS_CONTROL = 1, USBDEVFS_SETCONFIGURATION, USBDEVFS_GETDRIVER,
       USBDEVFS_IOCTL, USBDEVFS_DISCONNECT, USBDEVFS_CONNECT };
struct usbdevfs_ctrltransfer { unsigned char bRequestType, bRequest; unsigned short wValue, wIndex, wLength; unsigned int timeout; void *data; };
struct usbdevfs_getdriver { unsigned int interface; char driver[256]; };
struct usbdevfs_ioctl { int ifno, ioctl_code; void *data; };
static int initial_error, final_error, config_calls, test_detached, restored, unexpected_owner, disconnect_error, wrong_phone, test_active;
static int sibling_released, rebind_result, detached_mask, rebind_siblings;
static int mock_ioctl(int fd, int request, void *argument) {
    assert(fd == 42);
    if (request == USBDEVFS_CONTROL) {
        struct usbdevfs_ctrltransfer *c = argument;
        unsigned char *bytes = c->data;
        if (c->bRequest == 6) {
            bytes[0] = 18; bytes[1] = 1; bytes[8] = wrong_phone ? 0 : 0xac; bytes[9] = 5;
            bytes[10] = 0xa8; bytes[11] = 0x12; return 18;
        }
        assert(c->bRequest == 8); bytes[0] = (unsigned char) test_active; return 1;
    }
    if (request == USBDEVFS_SETCONFIGURATION) {
        assert(*(unsigned int *)argument == 6);
        errno = config_calls++ == 0 ? initial_error : final_error;
        return errno ? -1 : 0;
    }
    if (request == USBDEVFS_GETDRIVER) {
        struct usbdevfs_getdriver *d = argument;
        if (d->interface > 1) { errno = ENODATA; return -1; }
        if (detached_mask & (1 << d->interface)) { errno = ENODATA; return -1; }
        const char *owner = d->interface == 1 && unexpected_owner ? "usbfs" : "snd-usb-audio";
        memcpy(d->driver, owner, strlen(owner) + 1); return 0;
    }
    if (request == USBDEVFS_IOCTL) {
        struct usbdevfs_ioctl *op = argument;
        assert(op->ifno == 0 || op->ifno == 1);
        if (op->ioctl_code == USBDEVFS_DISCONNECT) {
            if (disconnect_error && op->ifno == 1) { errno = disconnect_error; return -1; }
            test_detached++;
            detached_mask |= sibling_released ? 3 : (1 << op->ifno);
            return 0;
        }
        assert(op->ioctl_code == USBDEVFS_CONNECT); restored++;
        if (rebind_siblings) detached_mask = 0;
        else detached_mask &= ~(1 << op->ifno);
        return rebind_result;
    }
    assert(0); return -1;
}
#define ioctl mock_ioctl
#include "../../main/jni/geely_usb_configuration.h"
#undef ioctl
static void reset(void) {
    initial_error = final_error = config_calls = test_detached = restored = unexpected_owner = disconnect_error = wrong_phone = 0;
    test_active = 2;
    sibling_released = rebind_result = detached_mask = rebind_siblings = 0;
}
int main(void) {
    reset(); assert(geely_configure_fd(42,6) == 0 && config_calls == 1 && test_detached == 0);
    reset(); initial_error = EBUSY; assert(geely_configure_fd(42,6) == 0 && config_calls == 2 && test_detached == 2 && restored == 0);
    reset(); initial_error = EBUSY; final_error = EIO;
    assert(geely_configure_fd(42,6) == -EIO && test_detached == 2 && restored == 2);
    reset(); initial_error = EBUSY; unexpected_owner = 1;
    assert(geely_configure_fd(42,6) == -EBUSY && test_detached == 0 && config_calls == 1);
    reset(); initial_error = EPERM; assert(geely_configure_fd(42,6) == -EPERM && test_detached == 0);
    reset(); initial_error = EBUSY; disconnect_error = EPERM;
    assert(geely_configure_fd(42,6) == -EPERM && test_detached == 1 && restored == 1);
    reset(); test_active = 6; assert(geely_configure_fd(42,6) == 0 && config_calls == 0 && test_detached == 0);
    reset(); wrong_phone = 1; assert(geely_configure_fd(42,6) == -ENODEV && config_calls == 0);
    reset(); test_active = 1; assert(geely_configure_fd(42,6) == -EINVAL && config_calls == 0);
    reset(); assert(geely_configure_fd(42,5) == -EINVAL && config_calls == 0);
    reset(); assert(geely_configure_fd(-1,6) == -EBADF && config_calls == 0);
    reset(); initial_error = EBUSY; sibling_released = 1;
    assert(geely_configure_fd(42,6) == 0 && test_detached == 1 && config_calls == 2);
    reset(); initial_error = EBUSY; sibling_released = 1; final_error = EBUSY; rebind_result = 1;
    assert(geely_configure_fd(42,6) == -EBUSY && restored == 2);
    reset(); initial_error = EBUSY; final_error = EBUSY; rebind_result = 1; rebind_siblings = 1;
    assert(geely_configure_fd(42,6) == -EBUSY && restored == 1 && detached_mask == 0);
    puts("PASS: 14 native configuration/driver recovery scenarios; mocked ioctl, no hardware claims.");
    return 0;
}
