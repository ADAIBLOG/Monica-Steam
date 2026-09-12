use jni::{
    objects::{JByteArray, JClass},
    sys::jbyteArray,
    JNIEnv,
};
use monica_steam_core::{
    auth_session::{
        parse_auth_session_info, parse_mobile_confirmation_success,
        parse_pending_login_client_ids, AuthSessionInfo,
    },
    authorized_device::{parse_authorized_devices, AuthorizedDevice, AuthorizedDeviceUsage},
};
use std::ptr;

const AUTH_CLIENT_IDS_BRIDGE_MAGIC: &[u8; 4] = b"MAC1";
const AUTH_SESSION_INFO_BRIDGE_MAGIC: &[u8; 4] = b"MAI1";
const AUTH_CONFIRMATION_BRIDGE_MAGIC: &[u8; 4] = b"MAR1";
const AUTHORIZED_DEVICES_BRIDGE_MAGIC: &[u8; 4] = b"MSD1";

fn write_required_string(out: &mut Vec<u8>, value: &str) -> Option<()> {
    let bytes = value.as_bytes();
    let length = u32::try_from(bytes.len()).ok()?;
    out.extend_from_slice(&length.to_le_bytes());
    out.extend_from_slice(bytes);
    Some(())
}

fn serialize_auth_client_ids(ids: &[i64]) -> Option<Vec<u8>> {
    let count = u32::try_from(ids.len()).ok()?;
    let mut out = Vec::with_capacity(8 + ids.len().saturating_mul(8));
    out.extend_from_slice(AUTH_CLIENT_IDS_BRIDGE_MAGIC);
    out.extend_from_slice(&count.to_le_bytes());
    for id in ids {
        out.extend_from_slice(&id.to_le_bytes());
    }
    Some(out)
}

fn serialize_auth_session_info(info: &AuthSessionInfo) -> Option<Vec<u8>> {
    let mut out = Vec::new();
    out.extend_from_slice(AUTH_SESSION_INFO_BRIDGE_MAGIC);
    out.extend_from_slice(&info.version.to_le_bytes());
    write_required_string(&mut out, &info.ip)?;
    write_required_string(&mut out, &info.city)?;
    write_required_string(&mut out, &info.country)?;
    write_required_string(&mut out, &info.device_name)?;
    Some(out)
}

fn serialize_auth_confirmation(success: bool) -> Vec<u8> {
    let mut out = Vec::with_capacity(8);
    out.extend_from_slice(AUTH_CONFIRMATION_BRIDGE_MAGIC);
    out.extend_from_slice(&(if success { 1i32 } else { 0i32 }).to_le_bytes());
    out
}

fn serialize_authorized_devices(devices: &[AuthorizedDevice]) -> Option<Vec<u8>> {
    let count = u32::try_from(devices.len()).ok()?;
    let mut out = Vec::new();
    out.extend_from_slice(AUTHORIZED_DEVICES_BRIDGE_MAGIC);
    out.extend_from_slice(&count.to_le_bytes());
    for device in devices {
        match device.token_id {
            Some(token_id) => {
                out.extend_from_slice(&1i32.to_le_bytes());
                out.extend_from_slice(&token_id.to_le_bytes());
            }
            None => {
                out.extend_from_slice(&0i32.to_le_bytes());
                out.extend_from_slice(&0u64.to_le_bytes());
            }
        }
        out.extend_from_slice(&device.platform_type.to_le_bytes());
        out.extend_from_slice(&(if device.logged_in { 1i32 } else { 0i32 }).to_le_bytes());
        out.extend_from_slice(&(if device.is_current { 1i32 } else { 0i32 }).to_le_bytes());
        write_required_string(&mut out, &device.description)?;
        write_optional_usage(&mut out, device.first_seen.as_ref())?;
        write_optional_usage(&mut out, device.last_seen.as_ref())?;
    }
    Some(out)
}

fn write_optional_usage(out: &mut Vec<u8>, usage: Option<&AuthorizedDeviceUsage>) -> Option<()> {
    let Some(usage) = usage else {
        out.extend_from_slice(&0i32.to_le_bytes());
        return Some(());
    };
    out.extend_from_slice(&1i32.to_le_bytes());
    out.extend_from_slice(&usage.time_seconds.to_le_bytes());
    write_required_string(out, &usage.country)?;
    write_required_string(out, &usage.state)?;
    write_required_string(out, &usage.city)?;
    Some(())
}

#[no_mangle]
pub extern "system" fn Java_takagi_ru_monica_steam_core_RustSteamCoreNative_nativeParsePendingLoginClientIds(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    response: JByteArray<'_>,
) -> jbyteArray {
    let Ok(response) = env.convert_byte_array(&response) else {
        return ptr::null_mut();
    };
    let Ok(ids) = parse_pending_login_client_ids(&response) else {
        return ptr::null_mut();
    };
    let Some(encoded) = serialize_auth_client_ids(&ids) else {
        return ptr::null_mut();
    };
    env.byte_array_from_slice(&encoded)
        .map(|result| result.into_raw())
        .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_takagi_ru_monica_steam_core_RustSteamCoreNative_nativeParseAuthSessionInfo(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    response: JByteArray<'_>,
) -> jbyteArray {
    let Ok(response) = env.convert_byte_array(&response) else {
        return ptr::null_mut();
    };
    let Ok(info) = parse_auth_session_info(&response) else {
        return ptr::null_mut();
    };
    let Some(encoded) = serialize_auth_session_info(&info) else {
        return ptr::null_mut();
    };
    env.byte_array_from_slice(&encoded)
        .map(|result| result.into_raw())
        .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_takagi_ru_monica_steam_core_RustSteamCoreNative_nativeParseAuthConfirmation(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    response: JByteArray<'_>,
) -> jbyteArray {
    let Ok(response) = env.convert_byte_array(&response) else {
        return ptr::null_mut();
    };
    let Ok(success) = parse_mobile_confirmation_success(&response) else {
        return ptr::null_mut();
    };
    let encoded = serialize_auth_confirmation(success);
    env.byte_array_from_slice(&encoded)
        .map(|result| result.into_raw())
        .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_takagi_ru_monica_steam_core_RustSteamCoreNative_nativeParseAuthorizedDevices(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    response: JByteArray<'_>,
) -> jbyteArray {
    let Ok(response) = env.convert_byte_array(&response) else {
        return ptr::null_mut();
    };
    let Ok(devices) = parse_authorized_devices(&response) else {
        return ptr::null_mut();
    };
    let Some(encoded) = serialize_authorized_devices(&devices) else {
        return ptr::null_mut();
    };
    env.byte_array_from_slice(&encoded)
        .map(|result| result.into_raw())
        .unwrap_or(ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn auth_session_bridges_have_stable_versions() {
        let ids = serialize_auth_client_ids(&[7, 42]).unwrap();
        assert_eq!(&ids[0..4], b"MAC1");
        assert_eq!(u32::from_le_bytes(ids[4..8].try_into().unwrap()), 2);

        let info = serialize_auth_session_info(&AuthSessionInfo {
            version: 3,
            ip: "127.0.0.1".to_string(),
            city: "City".to_string(),
            country: "Country".to_string(),
            device_name: "Phone".to_string(),
        })
        .unwrap();
        assert_eq!(&info[0..4], b"MAI1");
        assert_eq!(i32::from_le_bytes(info[4..8].try_into().unwrap()), 3);

        let confirmed = serialize_auth_confirmation(true);
        assert_eq!(&confirmed[0..4], b"MAR1");
        assert_eq!(i32::from_le_bytes(confirmed[4..8].try_into().unwrap()), 1);
    }

    #[test]
    fn authorized_devices_bridge_layout_is_stable() {
        let encoded = serialize_authorized_devices(&[AuthorizedDevice {
            token_id: Some(u64::MAX - 1),
            description: "This phone".to_string(),
            platform_type: 3,
            logged_in: true,
            first_seen: Some(AuthorizedDeviceUsage {
                time_seconds: 10,
                country: "US".to_string(),
                state: "CA".to_string(),
                city: "San Francisco".to_string(),
            }),
            last_seen: None,
            is_current: true,
        }])
        .unwrap();

        assert_eq!(&encoded[0..4], b"MSD1");
        assert_eq!(u32::from_le_bytes(encoded[4..8].try_into().unwrap()), 1);
        assert_eq!(i32::from_le_bytes(encoded[8..12].try_into().unwrap()), 1);
        assert_eq!(u64::from_le_bytes(encoded[12..20].try_into().unwrap()), u64::MAX - 1);
        assert_eq!(i32::from_le_bytes(encoded[20..24].try_into().unwrap()), 3);
        assert_eq!(i32::from_le_bytes(encoded[24..28].try_into().unwrap()), 1);
        assert_eq!(i32::from_le_bytes(encoded[28..32].try_into().unwrap()), 1);
    }
}
