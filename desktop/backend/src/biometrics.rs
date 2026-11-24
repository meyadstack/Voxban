use serde::Serialize;
use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BiometricStatus {
    /// True only when fingerprint / biometric hardware is present AND configured on this PC/Mac.
    pub available: bool,
    /// True when the user has enrolled this vault for Fingerprint / Touch ID unlock.
    pub enrolled: bool,
    /// Human-readable sensor label, e.g. "Touch ID" or "Fingerprint (Windows Hello)".
    pub label: String,
}

#[derive(Debug, Clone)]
pub struct HardwareBiometrics {
    pub available: bool,
    pub label: String,
}

/// Checks whether fingerprint / biometric authentication hardware is present and ready on this machine.
pub fn detect_hardware() -> HardwareBiometrics {
    platform::detect_hardware()
}

/// Returns complete status combining OS hardware presence and vault enrollment state.
pub fn status(data_dir: &Path, vault_exists: bool) -> BiometricStatus {
    let hw = detect_hardware();
    let enrolled = hw.available && vault_exists && has_enrolled_secret(data_dir);
    BiometricStatus {
        available: hw.available,
        enrolled,
        label: hw.label,
    }
}

/// Triggers the native OS Fingerprint / Touch ID prompt (blocking — call via `tokio::task::spawn_blocking`).
pub fn verify_user(reason: &str) -> Result<(), String> {
    platform::verify_user(reason)
}

/// Stores the vault master passphrase in OS-protected secure storage (macOS Keychain / Windows DPAPI).
pub fn store_enrolled_secret(data_dir: &Path, passphrase: &str) -> Result<(), String> {
    platform::store_secret(data_dir, passphrase)
}

/// Retrieves the OS-protected vault master passphrase. Call ONLY after `verify_user` succeeds!
pub fn load_enrolled_secret(data_dir: &Path) -> Result<String, String> {
    platform::load_secret(data_dir)
}

/// Checks whether a biometric secret is enrolled for this vault directory.
pub fn has_enrolled_secret(data_dir: &Path) -> bool {
    platform::has_secret(data_dir)
}

/// Removes the stored biometric secret for this vault directory.
pub fn clear_enrolled_secret(data_dir: &Path) {
    platform::clear_secret(data_dir)
}

#[allow(dead_code)]
fn scoped_account_key(data_dir: &Path) -> String {
    let mut hasher = Sha256::new();
    hasher.update(data_dir.to_string_lossy().as_bytes());
    let digest = hasher.finalize();
    let hex: String = digest.iter().take(8).map(|b| format!("{b:02x}")).collect();
    format!("vault-master-{hex}")
}

#[allow(dead_code)]
fn dpapi_blob_path(data_dir: &Path) -> PathBuf {
    data_dir.join("vault.bio.dpapi")
}

// ================================================================ macOS (Touch ID + Login Keychain)

#[cfg(target_os = "macos")]
mod platform {
    use super::{scoped_account_key, HardwareBiometrics};
    use std::ffi::{CStr, CString};
    use std::os::raw::c_char;
    use std::path::Path;

    const SERVICE: &str = "com.voicekanban.desktop.vault";

    extern "C" {
        fn voxban_macos_biometry_type() -> i32;
        fn voxban_macos_authenticate_biometrics(
            reason_utf8: *const c_char,
            err_buf: *mut c_char,
            err_buf_len: usize,
        ) -> i32;
        fn voxban_macos_keychain_set(
            service_utf8: *const c_char,
            account_utf8: *const c_char,
            secret_utf8: *const c_char,
        ) -> i32;
        fn voxban_macos_keychain_get(
            service_utf8: *const c_char,
            account_utf8: *const c_char,
            out_buf: *mut c_char,
            out_buf_len: usize,
        ) -> i32;
        fn voxban_macos_keychain_exists(
            service_utf8: *const c_char,
            account_utf8: *const c_char,
        ) -> i32;
        fn voxban_macos_keychain_delete(
            service_utf8: *const c_char,
            account_utf8: *const c_char,
        );
    }

    pub fn detect_hardware() -> HardwareBiometrics {
        let code = unsafe { voxban_macos_biometry_type() };
        match code {
            1 => HardwareBiometrics { available: true, label: "Touch ID".into() },
            2 => HardwareBiometrics { available: true, label: "Face ID".into() },
            3 => HardwareBiometrics { available: true, label: "Optic ID".into() },
            _ => HardwareBiometrics { available: false, label: "Touch ID".into() },
        }
    }

    pub fn verify_user(reason: &str) -> Result<(), String> {
        let c_reason = CString::new(reason).unwrap_or_else(|_| CString::new("Unlock Voxban Password Vault").unwrap());
        let mut err_buf = vec![0u8; 512];
        let ok = unsafe {
            voxban_macos_authenticate_biometrics(
                c_reason.as_ptr(),
                err_buf.as_mut_ptr() as *mut c_char,
                err_buf.len(),
            )
        };
        if ok == 1 {
            Ok(())
        } else {
            let msg = unsafe { CStr::from_ptr(err_buf.as_ptr() as *const c_char) }
                .to_string_lossy()
                .trim()
                .to_string();
            Err(if msg.is_empty() {
                "Touch ID verification cancelled.".into()
            } else {
                msg
            })
        }
    }

    pub fn store_secret(data_dir: &Path, passphrase: &str) -> Result<(), String> {
        let svc = CString::new(SERVICE).map_err(|_| "Invalid service".to_string())?;
        let acct = CString::new(scoped_account_key(data_dir)).map_err(|_| "Invalid account".to_string())?;
        let sec = CString::new(passphrase).map_err(|_| "Passphrase cannot contain null bytes".to_string())?;
        let status = unsafe { voxban_macos_keychain_set(svc.as_ptr(), acct.as_ptr(), sec.as_ptr()) };
        if status == 0 {
            Ok(())
        } else {
            Err(format!("macOS Keychain error (OSStatus {status})."))
        }
    }

    pub fn load_secret(data_dir: &Path) -> Result<String, String> {
        let svc = CString::new(SERVICE).map_err(|_| "Invalid service".to_string())?;
        let acct = CString::new(scoped_account_key(data_dir)).map_err(|_| "Invalid account".to_string())?;
        let mut out = vec![0u8; 4096];
        let len = unsafe {
            voxban_macos_keychain_get(
                svc.as_ptr(),
                acct.as_ptr(),
                out.as_mut_ptr() as *mut c_char,
                out.len(),
            )
        };
        if len < 0 {
            return Err("No saved Touch ID credential found. Unlock once with your master password to re-enable.".into());
        }
        String::from_utf8(out[..len as usize].to_vec())
            .map_err(|_| "Corrupted Keychain vault passphrase.".into())
    }

    pub fn has_secret(data_dir: &Path) -> bool {
        let Ok(svc) = CString::new(SERVICE) else { return false };
        let Ok(acct) = CString::new(scoped_account_key(data_dir)) else { return false };
        unsafe { voxban_macos_keychain_exists(svc.as_ptr(), acct.as_ptr()) == 1 }
    }

    pub fn clear_secret(data_dir: &Path) {
        if let (Ok(svc), Ok(acct)) = (CString::new(SERVICE), CString::new(scoped_account_key(data_dir))) {
            unsafe { voxban_macos_keychain_delete(svc.as_ptr(), acct.as_ptr()) };
        }
    }
}

// ================================================================ Windows (Windows Hello Fingerprint + DPAPI)

#[cfg(target_os = "windows")]
mod platform {
    use super::{dpapi_blob_path, HardwareBiometrics};
    use std::fs;
    use std::path::Path;
    use std::ptr::null_mut;
    use windows::core::{HSTRING, PCWSTR};
    use windows::Security::Credentials::UI::{
        UserConsentVerificationResult, UserConsentVerifier, UserConsentVerifierAvailability,
    };
    use windows::Win32::Devices::BiometricFramework::{
        WinBioEnumBiometricUnits, WinBioFree, WINBIO_UNIT_SCHEMA,
    };
    use windows::Win32::Foundation::{LocalFree, HLOCAL};
    use windows::Win32::Security::Cryptography::{
        CryptProtectData, CryptUnprotectData, CRYPTPROTECT_UI_FORBIDDEN, CRYPT_INTEGER_BLOB,
    };

    /// Official Win32 `WINBIO_TYPE_FINGERPRINT` constant from `winbio_types.h`.
    const WINBIO_TYPE_FINGERPRINT: u32 = 0x0000_0008;

    fn detect_fingerprint_units() -> Option<usize> {
        let mut schemas: *mut WINBIO_UNIT_SCHEMA = null_mut();
        let mut count: usize = 0;
        let res = unsafe { WinBioEnumBiometricUnits(WINBIO_TYPE_FINGERPRINT, &mut schemas, &mut count) };
        if !schemas.is_null() {
            let _ = unsafe { WinBioFree(schemas.cast()) };
        }
        res.ok().map(|_| count)
    }

    pub fn detect_hardware() -> HardwareBiometrics {
        let availability = UserConsentVerifier::CheckAvailabilityAsync()
            .and_then(|op| op.get())
            .unwrap_or(UserConsentVerifierAvailability::DeviceNotPresent);

        if availability != UserConsentVerifierAvailability::Available {
            return HardwareBiometrics {
                available: false,
                label: "Fingerprint (Windows Hello)".into(),
            };
        }

        match detect_fingerprint_units() {
            Some(0) => HardwareBiometrics {
                // No physical fingerprint sensor found on this Windows PC
                available: false,
                label: "Fingerprint (Windows Hello)".into(),
            },
            Some(_) => HardwareBiometrics {
                available: true,
                label: "Fingerprint (Windows Hello)".into(),
            },
            None => HardwareBiometrics {
                // Enhanced Sign-in Security (ESS) restricts direct WBF enumeration while Windows Hello is ready
                available: true,
                label: "Windows Hello".into(),
            },
        }
    }

    pub fn verify_user(reason: &str) -> Result<(), String> {
        let msg = HSTRING::from(reason);
        let result = UserConsentVerifier::RequestVerificationAsync(&msg)
            .and_then(|op| op.get())
            .map_err(|e| format!("Windows Hello error: {e}"))?;

        match result {
            UserConsentVerificationResult::Verified => Ok(()),
            UserConsentVerificationResult::Canceled => Err("Fingerprint verification cancelled.".into()),
            UserConsentVerificationResult::DeviceNotPresent => Err("No fingerprint reader detected on this PC.".into()),
            UserConsentVerificationResult::NotConfiguredForUser => {
                Err("Windows Hello fingerprint is not configured in Windows Settings.".into())
            }
            UserConsentVerificationResult::RetriesExhausted => {
                Err("Too many failed fingerprint attempts. Use your master password.".into())
            }
            _ => Err("Fingerprint verification failed.".into()),
        }
    }

    pub fn store_secret(data_dir: &Path, passphrase: &str) -> Result<(), String> {
        let bytes = passphrase.as_bytes();
        let input = CRYPT_INTEGER_BLOB {
            cbData: bytes.len() as u32,
            pbData: bytes.as_ptr() as *mut u8,
        };
        let mut output = CRYPT_INTEGER_BLOB::default();
        unsafe {
            CryptProtectData(
                &input,
                PCWSTR::null(),
                None,
                None,
                None,
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
            .map_err(|e| format!("Windows DPAPI protect failed: {e}"))?;
        }
        let protected = unsafe {
            let slice = std::slice::from_raw_parts(output.pbData, output.cbData as usize);
            let vec = slice.to_vec();
            let _ = LocalFree(HLOCAL(output.pbData.cast()));
            vec
        };
        let path = dpapi_blob_path(data_dir);
        let tmp = path.with_extension("tmp");
        fs::write(&tmp, &protected).map_err(|e| e.to_string())?;
        fs::rename(&tmp, &path).map_err(|e| e.to_string())?;
        Ok(())
    }

    pub fn load_secret(data_dir: &Path) -> Result<String, String> {
        let path = dpapi_blob_path(data_dir);
        let encrypted = fs::read(&path)
            .map_err(|_| "No saved fingerprint credential found. Unlock once with your master password.".to_string())?;
        let input = CRYPT_INTEGER_BLOB {
            cbData: encrypted.len() as u32,
            pbData: encrypted.as_ptr() as *mut u8,
        };
        let mut output = CRYPT_INTEGER_BLOB::default();
        unsafe {
            CryptUnprotectData(
                &input,
                None,
                None,
                None,
                None,
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )
            .map_err(|e| format!("Windows DPAPI unprotect failed: {e}"))?;
        }
        let decrypted = unsafe {
            let slice = std::slice::from_raw_parts(output.pbData, output.cbData as usize);
            let vec = slice.to_vec();
            let _ = LocalFree(HLOCAL(output.pbData.cast()));
            vec
        };
        String::from_utf8(decrypted).map_err(|_| "Corrupted biometric vault credential.".into())
    }

    pub fn has_secret(data_dir: &Path) -> bool {
        dpapi_blob_path(data_dir).exists()
    }

    pub fn clear_secret(data_dir: &Path) {
        let _ = fs::remove_file(dpapi_blob_path(data_dir));
    }
}

// ================================================================ Other OS fallback

#[cfg(not(any(target_os = "macos", target_os = "windows")))]
mod platform {
    use super::HardwareBiometrics;
    use std::path::Path;

    pub fn detect_hardware() -> HardwareBiometrics {
        HardwareBiometrics {
            available: false,
            label: "Fingerprint".into(),
        }
    }

    pub fn verify_user(_reason: &str) -> Result<(), String> {
        Err("Biometric authentication is not supported on this OS.".into())
    }

    pub fn store_secret(_data_dir: &Path, _passphrase: &str) -> Result<(), String> {
        Err("Biometric storage is not supported on this OS.".into())
    }

    pub fn load_secret(_data_dir: &Path) -> Result<String, String> {
        Err("Biometric storage is not supported on this OS.".into())
    }

    pub fn has_secret(_data_dir: &Path) -> bool {
        false
    }

    pub fn clear_secret(_data_dir: &Path) {}
}
