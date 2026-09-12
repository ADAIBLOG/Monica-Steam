use jni::{
    objects::{JByteArray, JClass},
    sys::jbyteArray,
    JNIEnv,
};
use monica_steam_core::{
    group_chat::{parse_group_chat_history, GroupChatHistoryPage, GroupChatReaction},
    group_summary::{parse_group_summaries, GroupRoomSummary, GroupSummary},
};
use std::ptr;

const GROUP_CHAT_HISTORY_BRIDGE_MAGIC: &[u8; 4] = b"MSG1";
const GROUP_SUMMARIES_BRIDGE_MAGIC: &[u8; 4] = b"MGS1";

fn write_required_string(out: &mut Vec<u8>, value: &str) -> Option<()> {
    write_required_bytes(out, value.as_bytes())
}

fn write_required_bytes(out: &mut Vec<u8>, value: &[u8]) -> Option<()> {
    let length = u32::try_from(value.len()).ok()?;
    out.extend_from_slice(&length.to_le_bytes());
    out.extend_from_slice(value);
    Some(())
}

fn serialize_reaction(out: &mut Vec<u8>, reaction: &GroupChatReaction) -> Option<()> {
    out.extend_from_slice(&reaction.kind.to_le_bytes());
    out.extend_from_slice(&reaction.count.to_le_bytes());
    out.extend_from_slice(&(if reaction.has_user_reacted { 1i32 } else { 0i32 }).to_le_bytes());
    write_required_string(out, &reaction.name)
}

fn serialize_group_chat_history(page: &GroupChatHistoryPage) -> Option<Vec<u8>> {
    let count = u32::try_from(page.messages.len()).ok()?;
    let mut out = Vec::new();
    out.extend_from_slice(GROUP_CHAT_HISTORY_BRIDGE_MAGIC);
    out.extend_from_slice(&count.to_le_bytes());
    out.extend_from_slice(&(if page.more_available { 1i32 } else { 0i32 }).to_le_bytes());

    for message in &page.messages {
        out.extend_from_slice(&message.sender_account_id.to_le_bytes());
        out.extend_from_slice(&message.timestamp.to_le_bytes());
        out.extend_from_slice(&message.ordinal.to_le_bytes());
        out.extend_from_slice(&(if message.deleted { 1i32 } else { 0i32 }).to_le_bytes());
        out.extend_from_slice(&message.event_type.to_le_bytes());
        write_required_string(&mut out, &message.raw_body)?;
        write_required_string(&mut out, &message.event_text)?;
        let reaction_count = u32::try_from(message.reactions.len()).ok()?;
        out.extend_from_slice(&reaction_count.to_le_bytes());
        for reaction in &message.reactions {
            serialize_reaction(&mut out, reaction)?;
        }
    }
    Some(out)
}

fn serialize_room(out: &mut Vec<u8>, room: &GroupRoomSummary) -> Option<()> {
    out.extend_from_slice(&room.chat_id.to_le_bytes());
    out.extend_from_slice(&room.sort_order.to_le_bytes());
    out.extend_from_slice(&room.last_message_timestamp.to_le_bytes());
    out.extend_from_slice(&room.last_sender_account_id.to_le_bytes());
    out.extend_from_slice(&room.last_acknowledged_timestamp.to_le_bytes());
    out.extend_from_slice(&(if room.voice_allowed { 1i32 } else { 0i32 }).to_le_bytes());
    write_required_string(out, &room.name)?;
    write_required_string(out, &room.last_message)?;
    let member_count = u32::try_from(room.voice_member_account_ids.len()).ok()?;
    out.extend_from_slice(&member_count.to_le_bytes());
    for account_id in &room.voice_member_account_ids {
        out.extend_from_slice(&account_id.to_le_bytes());
    }
    Some(())
}

fn serialize_group_summaries(groups: &[GroupSummary]) -> Option<Vec<u8>> {
    let count = u32::try_from(groups.len()).ok()?;
    let mut out = Vec::new();
    out.extend_from_slice(GROUP_SUMMARIES_BRIDGE_MAGIC);
    out.extend_from_slice(&count.to_le_bytes());
    for group in groups {
        out.extend_from_slice(&group.group_id.to_le_bytes());
        out.extend_from_slice(&group.owner_account_id.to_le_bytes());
        out.extend_from_slice(&group.active_member_count.to_le_bytes());
        out.extend_from_slice(&group.active_voice_member_count.to_le_bytes());
        out.extend_from_slice(&group.default_chat_id.to_le_bytes());
        out.extend_from_slice(&group.rank.to_le_bytes());
        write_required_string(&mut out, &group.name)?;
        write_required_string(&mut out, &group.tagline)?;
        write_required_bytes(&mut out, &group.avatar_ugc_raw)?;
        write_required_bytes(&mut out, &group.avatar_legacy_raw)?;
        let top_member_count = u32::try_from(group.top_member_account_ids.len()).ok()?;
        out.extend_from_slice(&top_member_count.to_le_bytes());
        for account_id in &group.top_member_account_ids {
            out.extend_from_slice(&account_id.to_le_bytes());
        }
        let room_count = u32::try_from(group.rooms.len()).ok()?;
        out.extend_from_slice(&room_count.to_le_bytes());
        for room in &group.rooms {
            serialize_room(&mut out, room)?;
        }
    }
    Some(out)
}

#[no_mangle]
pub extern "system" fn Java_takagi_ru_monica_steam_core_RustSteamCoreNative_nativeParseGroupChatHistory(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    response: JByteArray<'_>,
) -> jbyteArray {
    let Ok(response) = env.convert_byte_array(&response) else {
        return ptr::null_mut();
    };
    let Ok(page) = parse_group_chat_history(&response) else {
        return ptr::null_mut();
    };
    let Some(encoded) = serialize_group_chat_history(&page) else {
        return ptr::null_mut();
    };
    env.byte_array_from_slice(&encoded)
        .map(|result| result.into_raw())
        .unwrap_or(ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_takagi_ru_monica_steam_core_RustSteamCoreNative_nativeParseGroupChatSummaries(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    response: JByteArray<'_>,
) -> jbyteArray {
    let Ok(response) = env.convert_byte_array(&response) else {
        return ptr::null_mut();
    };
    let Ok(groups) = parse_group_summaries(&response) else {
        return ptr::null_mut();
    };
    let Some(encoded) = serialize_group_summaries(&groups) else {
        return ptr::null_mut();
    };
    env.byte_array_from_slice(&encoded)
        .map(|result| result.into_raw())
        .unwrap_or(ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use super::*;
    use monica_steam_core::group_chat::GroupChatHistoryMessage;

    #[test]
    fn group_chat_history_bridge_layout_is_stable() {
        let encoded = serialize_group_chat_history(&GroupChatHistoryPage {
            more_available: true,
            messages: vec![GroupChatHistoryMessage {
                sender_account_id: 39_734_274,
                timestamp: 300,
                ordinal: 2,
                raw_body: "Hello".to_string(),
                deleted: false,
                event_type: 0,
                event_text: String::new(),
                reactions: vec![GroupChatReaction {
                    kind: 2,
                    name: "party".to_string(),
                    count: 3,
                    has_user_reacted: true,
                }],
            }],
        })
        .unwrap();

        assert_eq!(&encoded[0..4], b"MSG1");
        assert_eq!(u32::from_le_bytes(encoded[4..8].try_into().unwrap()), 1);
        assert_eq!(i32::from_le_bytes(encoded[8..12].try_into().unwrap()), 1);
        assert_eq!(
            i64::from_le_bytes(encoded[12..20].try_into().unwrap()),
            39_734_274
        );
        assert_eq!(i64::from_le_bytes(encoded[20..28].try_into().unwrap()), 300);
        assert_eq!(i32::from_le_bytes(encoded[28..32].try_into().unwrap()), 2);
    }

    #[test]
    fn group_summaries_bridge_layout_is_stable() {
        let encoded = serialize_group_summaries(&[GroupSummary {
            group_id: 8001,
            name: "Monica testers".to_string(),
            tagline: "Play together".to_string(),
            owner_account_id: 39_734_274,
            active_member_count: 12,
            active_voice_member_count: 1,
            default_chat_id: 9001,
            rooms: vec![GroupRoomSummary {
                chat_id: 9001,
                name: "General".to_string(),
                sort_order: 1,
                last_message_timestamp: 200,
                last_message: "Hello".to_string(),
                last_sender_account_id: 39_734_274,
                last_acknowledged_timestamp: 150,
                voice_allowed: false,
                voice_member_account_ids: Vec::new(),
            }],
            rank: 50,
            avatar_ugc_raw: Vec::new(),
            avatar_legacy_raw: Vec::new(),
            top_member_account_ids: vec![39_734_274],
        }])
        .unwrap();

        assert_eq!(&encoded[0..4], b"MGS1");
        assert_eq!(u32::from_le_bytes(encoded[4..8].try_into().unwrap()), 1);
        assert_eq!(u64::from_le_bytes(encoded[8..16].try_into().unwrap()), 8001);
        assert_eq!(
            i64::from_le_bytes(encoded[16..24].try_into().unwrap()),
            39_734_274
        );
        assert_eq!(i32::from_le_bytes(encoded[24..28].try_into().unwrap()), 12);
    }
}
