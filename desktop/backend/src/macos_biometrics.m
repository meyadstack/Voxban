// Native macOS Touch ID / Biometrics + Keychain helper for Voxban Password Vault.
// Compiled only on macOS via build.rs (links LocalAuthentication, Security, Foundation).

#import <Foundation/Foundation.h>
#import <LocalAuthentication/LocalAuthentication.h>
#import <Security/Security.h>
#include <stdint.h>
#include <string.h>

// Biometry availability codes returned to Rust:
// 0 = unavailable / no fingerprint sensor / not enrolled in macOS
// 1 = Touch ID available
// 2 = Face ID available
// 3 = Optic ID available
int32_t voxban_macos_biometry_type(void) {
    @autoreleasepool {
        LAContext *ctx = [[LAContext alloc] init];
        NSError *err = nil;
        BOOL ok = [ctx canEvaluatePolicy:LAPolicyDeviceOwnerAuthenticationWithBiometrics error:&err];
        if (!ok) {
            return 0;
        }
        if (@available(macOS 10.13.2, *)) {
            switch (ctx.biometryType) {
                case LABiometryTypeTouchID:
                    return 1;
                case LABiometryTypeFaceID:
                    return 2;
                default:
                    #if defined(__MAC_14_0) || (defined(MAC_OS_VERSION_14_0) && __MAC_OS_X_VERSION_MAX_ALLOWED >= MAC_OS_VERSION_14_0)
                    if (@available(macOS 14.0, *)) {
                        if (ctx.biometryType == LABiometryTypeOpticID) {
                            return 3;
                        }
                    }
                    #endif
                    return 1;
            }
        }
        return 1;
    }
}

// Prompts the user with native macOS Touch ID dialog.
// Returns 1 on success, 0 on failure/cancel, and writes error detail into err_buf if provided.
int32_t voxban_macos_authenticate_biometrics(const char *reason_utf8, char *err_buf, size_t err_buf_len) {
    @autoreleasepool {
        LAContext *ctx = [[LAContext alloc] init];
        NSError *canErr = nil;
        if (![ctx canEvaluatePolicy:LAPolicyDeviceOwnerAuthenticationWithBiometrics error:&canErr]) {
            if (err_buf && err_buf_len > 0) {
                const char *msg = canErr ? [[canErr localizedDescription] UTF8String] : "Biometrics unavailable";
                snprintf(err_buf, err_buf_len, "%s", msg ? msg : "Biometrics unavailable");
            }
            return 0;
        }

        NSString *reason = reason_utf8
            ? [NSString stringWithUTF8String:reason_utf8]
            : @"Unlock your Voxban Password Vault";

        __block BOOL authOk = NO;
        __block NSString *authErrStr = nil;
        dispatch_semaphore_t sema = dispatch_semaphore_create(0);

        [ctx evaluatePolicy:LAPolicyDeviceOwnerAuthenticationWithBiometrics
            localizedReason:reason
                      reply:^(BOOL success, NSError * _Nullable error) {
            authOk = success;
            if (error) {
                authErrStr = [error localizedDescription];
            }
            dispatch_semaphore_signal(sema);
        }];

        dispatch_semaphore_wait(sema, DISPATCH_TIME_FOREVER);

        if (!authOk && err_buf && err_buf_len > 0) {
            const char *msg = authErrStr ? [authErrStr UTF8String] : "Authentication cancelled";
            snprintf(err_buf, err_buf_len, "%s", msg ? msg : "Authentication cancelled");
        }
        return authOk ? 1 : 0;
    }
}

// Stores UTF-8 secret in the user's macOS Login Keychain. Returns OSStatus (0 == errSecSuccess).
int32_t voxban_macos_keychain_set(const char *service_utf8, const char *account_utf8, const char *secret_utf8) {
    @autoreleasepool {
        NSString *service = [NSString stringWithUTF8String:service_utf8];
        NSString *account = [NSString stringWithUTF8String:account_utf8];
        NSData *data = [[NSString stringWithUTF8String:secret_utf8] dataUsingEncoding:NSUTF8StringEncoding];

        NSDictionary *query = @{
            (__bridge id)kSecClass: (__bridge id)kSecClassGenericPassword,
            (__bridge id)kSecAttrService: service,
            (__bridge id)kSecAttrAccount: account,
        };
        SecItemDelete((__bridge CFDictionaryRef)query);

        NSDictionary *attrs = @{
            (__bridge id)kSecClass: (__bridge id)kSecClassGenericPassword,
            (__bridge id)kSecAttrService: service,
            (__bridge id)kSecAttrAccount: account,
            (__bridge id)kSecAttrAccessible: (__bridge id)kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            (__bridge id)kSecValueData: data,
        };
        OSStatus status = SecItemAdd((__bridge CFDictionaryRef)attrs, NULL);
        return (int32_t)status;
    }
}

// Reads UTF-8 secret from macOS Login Keychain into out_buf. Returns byte length >= 0, or -1 if missing/error.
int32_t voxban_macos_keychain_get(const char *service_utf8, const char *account_utf8, char *out_buf, size_t out_buf_len) {
    @autoreleasepool {
        NSString *service = [NSString stringWithUTF8String:service_utf8];
        NSString *account = [NSString stringWithUTF8String:account_utf8];

        NSDictionary *query = @{
            (__bridge id)kSecClass: (__bridge id)kSecClassGenericPassword,
            (__bridge id)kSecAttrService: service,
            (__bridge id)kSecAttrAccount: account,
            (__bridge id)kSecReturnData: @YES,
            (__bridge id)kSecMatchLimit: (__bridge id)kSecMatchLimitOne,
        };

        CFTypeRef result = NULL;
        OSStatus status = SecItemCopyMatching((__bridge CFDictionaryRef)query, &result);
        if (status != errSecSuccess || result == NULL) {
            return -1;
        }
        NSData *data = (__bridge_transfer NSData *)result;
        if (![data isKindOfClass:[NSData class]]) {
            return -1;
        }
        NSUInteger len = [data length];
        if (len + 1 > out_buf_len) {
            return -2;
        }
        memcpy(out_buf, [data bytes], len);
        out_buf[len] = '\0';
        return (int32_t)len;
    }
}

// Checks whether a Keychain item exists without copying its secret bytes. Returns 1 if present, 0 otherwise.
int32_t voxban_macos_keychain_exists(const char *service_utf8, const char *account_utf8) {
    @autoreleasepool {
        NSString *service = [NSString stringWithUTF8String:service_utf8];
        NSString *account = [NSString stringWithUTF8String:account_utf8];

        NSDictionary *query = @{
            (__bridge id)kSecClass: (__bridge id)kSecClassGenericPassword,
            (__bridge id)kSecAttrService: service,
            (__bridge id)kSecAttrAccount: account,
            (__bridge id)kSecReturnAttributes: @YES,
            (__bridge id)kSecMatchLimit: (__bridge id)kSecMatchLimitOne,
        };
        CFTypeRef result = NULL;
        OSStatus status = SecItemCopyMatching((__bridge CFDictionaryRef)query, &result);
        if (result != NULL) {
            CFRelease(result);
        }
        return status == errSecSuccess ? 1 : 0;
    }
}

// Removes the Keychain entry.
void voxban_macos_keychain_delete(const char *service_utf8, const char *account_utf8) {
    @autoreleasepool {
        NSString *service = [NSString stringWithUTF8String:service_utf8];
        NSString *account = [NSString stringWithUTF8String:account_utf8];
        NSDictionary *query = @{
            (__bridge id)kSecClass: (__bridge id)kSecClassGenericPassword,
            (__bridge id)kSecAttrService: service,
            (__bridge id)kSecAttrAccount: account,
        };
        SecItemDelete((__bridge CFDictionaryRef)query);
    }
}
