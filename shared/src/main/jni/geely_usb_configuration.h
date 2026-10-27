/* GPL-3.0: bounded adaptation of programmerguohuajing/DiPlay-Legacy-Android
 * usb_host_compat.c at 90a9a09f62292aab6fffa0896f83749c70f47d9f.
 * Unlike the general path, only the observed H53 snd-usb-audio conflict is detached.
 * The includer provides usbfs types, ioctl, errno and string functions. */
static int geely_configure_fd(int fd, unsigned int target) {
    if (fd < 0) return -EBADF;
    if (target != 6) return -EINVAL;
    unsigned char descriptor[18] = {0};
    struct usbdevfs_ctrltransfer control;
    memset(&control, 0, sizeof(control));
    control.bRequestType = 0x80;
    control.bRequest = 6;
    control.wValue = 0x0100;
    control.wLength = sizeof(descriptor);
    control.timeout = 1000;
    control.data = descriptor;
    if (ioctl(fd, USBDEVFS_CONTROL, &control) != 18) return -ENODEV;
    if (descriptor[0] != 18 || descriptor[1] != 1 || descriptor[8] != 0xac || descriptor[9] != 0x05
            || descriptor[10] != 0xa8 || descriptor[11] != 0x12) return -ENODEV;
    unsigned char active = 0;
    control.bRequest = 8;
    control.wValue = 0;
    control.wLength = 1;
    control.data = &active;
    if (ioctl(fd, USBDEVFS_CONTROL, &control) != 1) return -EIO;
    if (active == target) return 0; /* Do not reset a working configuration. */
    if (active != 2) return -EINVAL;
    if (ioctl(fd, USBDEVFS_SETCONFIGURATION, &target) == 0) return 0;
    int result = -errno;
    if (result != -EBUSY) return result;
    unsigned int candidates[2], count = 0, detached = 0;
    /* Preflight all owners before any detach; never take a userspace or unknown driver. */
    for (unsigned int iface = 0; iface < 256; ++iface) {
        struct usbdevfs_getdriver driver;
        memset(&driver, 0, sizeof(driver));
        driver.interface = iface;
        if (ioctl(fd, USBDEVFS_GETDRIVER, &driver) != 0) {
            if (errno == ENODATA || errno == EINVAL || errno == ENOENT) continue;
            return -errno;
        }
        if (iface > 1 || strncmp(driver.driver, "snd-usb-audio", sizeof(driver.driver)) != 0) return -EBUSY;
        candidates[count++] = iface;
    }
    if (count == 0) return -EBUSY;
    for (unsigned int i = 0; i < count; ++i) {
        /* Recheck immediately: another application can acquire an interface after preflight. */
        struct usbdevfs_getdriver driver;
        memset(&driver, 0, sizeof(driver));
        driver.interface = candidates[i];
        if (ioctl(fd, USBDEVFS_GETDRIVER, &driver) != 0) {
            // Audio disconnect may release a sibling interface owned by the same driver.
            if (errno == ENODATA) { ++detached; continue; }
            result = -errno; goto restore;
        }
        if (strncmp(driver.driver, "snd-usb-audio", sizeof(driver.driver)) != 0) { result = -EBUSY; goto restore; }
        struct usbdevfs_ioctl operation;
        memset(&operation, 0, sizeof(operation));
        operation.ifno = (int)candidates[i];
        operation.ioctl_code = USBDEVFS_DISCONNECT;
        if (ioctl(fd, USBDEVFS_IOCTL, &operation) != 0) { result = -errno; goto restore; }
        ++detached;
    }
    if (ioctl(fd, USBDEVFS_SETCONFIGURATION, &target) == 0) return 0;
    result = -errno;
restore:
    /* A failed transition or hotplug must not rebind drivers onto a different configuration. */
    if (ioctl(fd, USBDEVFS_CONTROL, &control) != 1 || active != 2) return -EIO;
    for (unsigned int i = 0; i < detached; ++i) {
        struct usbdevfs_getdriver driver;
        memset(&driver, 0, sizeof(driver));
        driver.interface = candidates[i];
        if (ioctl(fd, USBDEVFS_GETDRIVER, &driver) == 0) {
            // Rebinding the audio control interface may already have restored its sibling.
            if (strncmp(driver.driver, "snd-usb-audio", sizeof(driver.driver)) != 0) result = -EBUSY;
            continue;
        }
        if (errno != ENODATA) { result = -EIO; continue; }
        struct usbdevfs_ioctl operation;
        memset(&operation, 0, sizeof(operation));
        operation.ifno = (int)candidates[i];
        operation.ioctl_code = USBDEVFS_CONNECT;
        // Linux 3.0 device_attach returns +1 for a successful rebind, not necessarily zero.
        if (ioctl(fd, USBDEVFS_IOCTL, &operation) < 0) result = -EIO;
        if (ioctl(fd, USBDEVFS_GETDRIVER, &driver) != 0
                || strncmp(driver.driver, "snd-usb-audio", sizeof(driver.driver)) != 0) result = -EIO;
    }
    return result;
}
