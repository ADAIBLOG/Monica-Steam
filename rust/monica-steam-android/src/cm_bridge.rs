use jni::{
    objects::{JByteArray, JClass, JString},
    sys::{jbyteArray, jint, jlong},
    JNIEnv,
};
use monica_steam_core::cm::{decode_messages, encode_message, web_logon_body, CmEnvelope};
use std::ptr;

const CM_BRIDGE_MAGIC: &[u8; 4] = b"MSC1";
const OPTIONAL_I32_NONE: i32 = i32::MIN;

fn read_jstring(env: &mut JNIEnv<'_>, value: &JString<'_>) -> Option<String> {
    env.get_string(value).ok().map(Into::into)
}

fn write_jbytes(env: &mut JNIEnv<'_>, value: &[u8]) -> jbyteArray {
    env.byte_array_from_slice(value)
        .map(|result| result.into_raw())
        .unwrap_or(ptr::null_mut())
}

fn write_optional_string(out: &mut Vec<u8>, value: Option<&str>) -> Option<()> {
    match value {
        Some(value) => {
            let bytes = value.as_bytes();
            let length = i32::try_from(bytes.len()).ok()?;
            out.extend_from_slice(&length.to_le_bytes());
            out.extend_from_slice(bytes);
        }
        None => out.extend_from_slice(&(-1i32).to_le_bytes()),
    }
    Some(())
}

fn serialize_cm_envelopes(messages: &[CmEnvelope]) -> Option<Vec<u8>> {
    let count = u32::try_from(messages.len()).ok()?;
    let mut out = Vec::new();
    out.extend_from_slice(CM_BRIDGE_MAGIC);
    out.extend_from_slice(&count.to_le_bytes());

    for message in messages {
        out.extend_from_slice(&message.e_msg.to_le_bytes());
        out.extend_from_slice(&message.header.steam_id.to_le_bytes());
        out.extend_from_slice(&message.header.session_id.to_le_bytes());
        out.extend_from_slice(&message.header.job_id_source.to_le_bytes());
        out.extend_from_slice(&message.header.job_id_target.to_le_bytes());
        out.extend_from_slice(&message.header.e_result.unwrap_or(OPTIONAL_I32_NONE).to_le_bytes());
        out.extend_from_slice(
            &message
                .header
                .transport_error
                .unwrap_or(OPTIONAL_I32_NONE)
                .to_le_bytes(),
        );
        write_optional_string(&mut out, message.header.target_job_name.as_deref())?;
        write_optional_string(&mut out, message.header.error_message.as_deref())?;
        let body_length = u32::try_from(message.body.len()).ok()?;
        out.extend_from_slice(&body_length.to_le_bytes());
        out.extend_from_slice(&message.body);
    }
    Some(out)
}

#[no_mangle]
pub extern "system" fn Java_takagi_ru_monica_steam_core_RustSteamCoreNative_nativeEncodeCmMessage(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    e_msg: jint,
    steam_id: jlong,
    session_id: jint,
    body: JByteArray<'_>,
    job_id_source: jlong,
    job_id_target: jlong,
    target_job_name: JString<'_>,
) -> jbyteArray {
    let Ok(body) = env.convert_byte_array(&body) else {
        return ptr::null_mut();
    };
    let Some(target_job_name) = read_jstring(&mut env, &target_job_name) else {
        return ptr::null_mut();
    };
    let target_job_name = if target_job_name.is_empty() {
        None
    } else {
        Some(target_job_name.as_str())
    };
    let Ok(encoded) = encode_message(
        e_msg,
        steam_id,
        session_id,
        &body,
        job_id_source,
        job_id_target,
        target_job_name,
    ) else {
        return ptr::null_mut();
    };
    write_jbytes(&mut env, &encoded)
}

#[no_mangle]
pub extern "system" fn Java_takagi_ru_monica_steam_core_RustSteamCoreNative_nativeDecodeCmMessages(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    payload: JByteArray<'_>,
) -> jbyteArray {
    let Ok(payload) = env.convert_byte_array(&payload) else {
        return ptr::null_mut();
    };
    let Ok(messages) = decode_messages(&payload) else {
        return ptr::null_mut();
    };
    let Some(encoded) = serialize_cm_envelopes(&messages) else {
        return ptr::null_mut();
    };
    write_jbytes(&mut env, &encoded)
}

#[no_mangle]
pub extern "system" fn Java_takagi_ru_monica_steam_core_RustSteamCoreNative_nativeWebLogonBody(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    web_logon_token: JString<'_>,
) -> jbyteArray {
    let Some(web_logon_token) = read_jstring(&mut env, &web_logon_token) else {
        return ptr::null_mut();
    };
    let Ok(encoded) = web_logon_body(&web_logon_token) else {
        return ptr::null_mut();
    };
    write_jbytes(&mut env, &encoded)
}
