use jni::{
    objects::{JByteArray, JClass},
    sys::jbyteArray,
    JNIEnv,
};
use monica_steam_core::friend_nickname::{parse_friend_nicknames, FriendNickname};
use std::ptr;

const FRIEND_NICKNAMES_BRIDGE_MAGIC: &[u8; 4] = b"MSN1";

fn write_required_string(out: &mut Vec<u8>, value: &str) -> Option<()> {
    let bytes = value.as_bytes();
    let length = u32::try_from(bytes.len()).ok()?;
    out.extend_from_slice(&length.to_le_bytes());
    out.extend_from_slice(bytes);
    Some(())
}

fn serialize_friend_nicknames(items: &[FriendNickname]) -> Option<Vec<u8>> {
    let count = u32::try_from(items.len()).ok()?;
    let mut out = Vec::new();
    out.extend_from_slice(FRIEND_NICKNAMES_BRIDGE_MAGIC);
    out.extend_from_slice(&count.to_le_bytes());
    for item in items {
        out.extend_from_slice(&item.steam_id.to_le_bytes());
        write_required_string(&mut out, &item.nickname)?;
    }
    Some(out)
}

#[no_mangle]
pub extern "system" fn Java_takagi_ru_monica_steam_core_RustSteamCoreNative_nativeParseFriendNicknames(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    response: JByteArray<'_>,
) -> jbyteArray {
    let Ok(response) = env.convert_byte_array(&response) else {
        return ptr::null_mut();
    };
    let Ok(items) = parse_friend_nicknames(&response) else {
        return ptr::null_mut();
    };
    let Some(encoded) = serialize_friend_nicknames(&items) else {
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
    fn nickname_bridge_layout_is_stable() {
        let encoded = serialize_friend_nicknames(&[FriendNickname {
            steam_id: 76_561_198_000_000_002,
            nickname: "Alice".to_string(),
        }])
        .unwrap();

        assert_eq!(&encoded[0..4], b"MSN1");
        assert_eq!(u32::from_le_bytes(encoded[4..8].try_into().unwrap()), 1);
        assert_eq!(
            i64::from_le_bytes(encoded[8..16].try_into().unwrap()),
            76_561_198_000_000_002
        );
    }
}
