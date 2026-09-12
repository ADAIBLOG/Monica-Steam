use jni::{
    objects::{JByteArray, JClass},
    sys::jbyteArray,
    JNIEnv,
};
use monica_steam_core::chat::{
    parse_chat_messages, parse_chat_sessions, ChatPage, ChatReactionKind, ChatSession,
};
use std::ptr;

const CHAT_SESSIONS_BRIDGE_MAGIC: &[u8; 4] = b"MSS1";
const CHAT_MESSAGES_BRIDGE_MAGIC: &[u8; 4] = b"MSM1";

fn write_required_string(out: &mut Vec<u8>, value: &str) -> Option<()> {
    let bytes = value.as_bytes();
    let length = u32::try_from(bytes.len()).ok()?;
    out.extend_from_slice(&length.to_le_bytes());
    out.extend_from_slice(bytes);
    Some(())
}

fn serialize_chat_sessions(sessions: &[ChatSession]) -> Option<Vec<u8>> {
    let count = u32::try_from(sessions.len()).ok()?;
    let mut out = Vec::with_capacity(8 + sessions.len().saturating_mul(28));
    out.extend_from_slice(CHAT_SESSIONS_BRIDGE_MAGIC);
    out.extend_from_slice(&count.to_le_bytes());
    for session in sessions {
        out.extend_from_slice(&session.partner_steam_id.to_le_bytes());
        out.extend_from_slice(&session.last_message_timestamp.to_le_bytes());
        out.extend_from_slice(&session.last_view_timestamp.to_le_bytes());
        out.extend_from_slice(&session.unread_count.to_le_bytes());
    }
    Some(out)
}

fn serialize_chat_page(page: &ChatPage) -> Option<Vec<u8>> {
    let count = u32::try_from(page.messages.len()).ok()?;
    let mut out = Vec::new();
    out.extend_from_slice(CHAT_MESSAGES_BRIDGE_MAGIC);
    out.extend_from_slice(&count.to_le_bytes());
    out.extend_from_slice(&(if page.more_available { 1i32 } else { 0i32 }).to_le_bytes());

    for message in &page.messages {
        out.extend_from_slice(&message.sender_steam_id.to_le_bytes());
        out.extend_from_slice(&message.timestamp.to_le_bytes());
        out.extend_from_slice(&message.ordinal.to_le_bytes());
        write_required_string(&mut out, &message.body)?;
        let reaction_count = u32::try_from(message.reactions.len()).ok()?;
        out.extend_from_slice(&reaction_count.to_le_bytes());
        for reaction in &message.reactions {
            let kind = match reaction.kind {
                ChatReactionKind::Emoticon => 1i32,
                ChatReactionKind::Sticker => 2i32,
            };
            out.extend_from_slice(&kind.to_le_bytes());
            write_required_string(&mut out, &reaction.name)?;
            let reactor_count = u32::try_from(reaction.reactor_steam_ids.len()).ok()?;
            out.extend_from_slice(&reactor_count.to_le_bytes());
            for steam_id in &reaction.reactor_steam_ids {
                out.extend_from_slice(&steam_id.to_le_bytes());
            }
        }
    }
    Some(out)
}

#[no_mangle]
pub extern "system" fn Java_takagi_ru_monica_steam_core_RustSteamCoreNative_nativeParseChatSessions(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    response: JByteArray<'_>,
) -> jbyteArray {
    let Ok(response) = env.convert_byte_array(&response) else {
        return ptr::null_mut();
    };
    let Ok(sessions) = parse_chat_sessions(&response) else {
        return ptr::null_mut();
    };
    let Some(encoded) = serialize_chat_sessions(&sessions) else {
        return ptr::null_mut();
    };
    env.byte_array_from_slice(&encoded)
        .map(|result| result.into_raw())
        .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_takagi_ru_monica_steam_core_RustSteamCoreNative_nativeParseChatMessages(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    response: JByteArray<'_>,
) -> jbyteArray {
    let Ok(response) = env.convert_byte_array(&response) else {
        return ptr::null_mut();
    };
    let Ok(page) = parse_chat_messages(&response) else {
        return ptr::null_mut();
    };
    let Some(encoded) = serialize_chat_page(&page) else {
        return ptr::null_mut();
    };
    env.byte_array_from_slice(&encoded)
        .map(|result| result.into_raw())
        .unwrap_or(ptr::null_mut())
}
