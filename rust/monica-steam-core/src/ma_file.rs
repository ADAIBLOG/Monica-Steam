use base64::{
    engine::general_purpose::STANDARD,
    engine::{GeneralPurpose, GeneralPurposeConfig},
    Engine as _,
};
use serde_json::{Map, Value};
use sha2::{Digest, Sha256};

const MAX_MAFILE_BYTES: usize = 16 * 1024 * 1024;
const STEAM_SECRET_BYTES: usize = 20;
const BASE32_ALPHABET: &str = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
const STEAM_ID64_BASE: u64 = 76_561_197_960_265_728;
const STEAM_ACCOUNT_ID32_MAX: u64 = 4_294_967_295;
const MONICA_LOCAL_STEAM_ID_PREFIX: &str = "monica-missing-steamid-";
const STEAM_ID_KEYS: &[&str] = &[
    "steamid",
    "steam_id",
    "SteamID",
    "steam64",
    "steam_id64",
    "steamID64",
    "SteamID64",
    "sbeamid",
];

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MaFilePayload {
    pub steam_id: String,
    pub account_name: String,
    pub display_name: String,
    pub device_id: String,
    pub shared_secret: String,
    pub identity_secret: Option<String>,
    pub revocation_code: Option<String>,
    pub token_gid: Option<String>,
    pub access_token: Option<String>,
    pub refresh_token: Option<String>,
    pub steam_login_secure: Option<String>,
    pub raw_json: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MaFileParseError {
    PayloadTooLarge,
    InvalidJson,
    MissingSharedSecret,
    InvalidSharedSecret,
    MissingSteamId,
    SessionOnlyMissingSteamId,
    InvalidSteamIdOverride,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum SharedSecretSource {
    StandardField,
    OtpAuthUri,
    SteamUri,
}

pub fn parse_ma_file_json(
    plain_json: &str,
    file_name: Option<&str>,
    display_name_override: Option<&str>,
    steam_id_override: Option<&str>,
    allow_missing_steam_id: bool,
) -> Result<MaFilePayload, MaFileParseError> {
    if plain_json.len() > MAX_MAFILE_BYTES {
        return Err(MaFileParseError::PayloadTooLarge);
    }

    let value: Value = serde_json::from_str(plain_json).map_err(|_| MaFileParseError::InvalidJson)?;
    let mut root = value.as_object().cloned().ok_or(MaFileParseError::InvalidJson)?;
    let session = object_any(&root, &["Session", "session"]);

    let embedded_steam_id = steam_id_any(&root)
        .or_else(|| session.and_then(steam_id_any))
        .or_else(|| {
            string_any(&root, &["steamLoginSecure", "steam_login_secure"])
                .and_then(|value| steam_id_from_steam_login_secure(&value))
        })
        .or_else(|| {
            session
                .and_then(|value| string_any(value, &["SteamLoginSecure", "steamLoginSecure"]))
                .and_then(|value| steam_id_from_steam_login_secure(&value))
        })
        .or_else(|| file_name.and_then(steam_id_from_file_name));

    let account_name = string_any(&root, &["account_name", "accountName", "AccountName"])
        .or_else(|| {
            session.and_then(|value| string_any(value, &["AccountName", "account_name"]))
        })
        .or_else(|| embedded_steam_id.clone())
        .or_else(|| file_name.and_then(account_name_from_file_name))
        .unwrap_or_else(|| "Steam".to_string());

    let preferred_shared_secret = preferred_shared_secret(&root);
    let has_preferred_shared_secret = preferred_shared_secret.is_some();
    let session_only_marker = bool_any(
        &root,
        &["monica_session_only_login", "monicaSessionOnlyLogin"],
    ) == Some(true);
    let has_session_fields = string_any(
        &root,
        &[
            "access_token",
            "accessToken",
            "oauth_token",
            "OAuthToken",
            "refresh_token",
            "refreshToken",
            "steamLoginSecure",
            "steam_login_secure",
        ],
    )
    .is_some()
        || session
            .and_then(|value| {
                string_any(
                    value,
                    &[
                        "AccessToken",
                        "access_token",
                        "OAuthToken",
                        "oauth_token",
                        "RefreshToken",
                        "refresh_token",
                        "SteamLoginSecure",
                        "steamLoginSecure",
                    ],
                )
            })
            .is_some();

    if !has_preferred_shared_secret && !(session_only_marker && has_session_fields) {
        return Err(MaFileParseError::MissingSharedSecret);
    }
    let shared_secret = match preferred_shared_secret {
        Some((raw, source)) => normalize_steam_shared_secret(&raw, source)?,
        None => String::new(),
    };

    let identity_secret = string_any(&root, &["identity_secret", "identitySecret"]);
    let revocation_code = string_any(&root, &["revocation_code", "revocationCode"]);
    let token_gid = string_any(&root, &["token_gid", "tokenGid"]);

    let steam_id = if let Some(value) = normalized_steam_id_override(steam_id_override)? {
        value
    } else if let Some(value) = embedded_steam_id {
        value
    } else if let Some(value) = local_steam_id_any(&root) {
        value
    } else if allow_missing_steam_id
        || bool_any(&root, &["monica_missing_steamid", "monicaMissingSteamId"]) == Some(true)
    {
        generate_local_steam_id(
            &account_name,
            &shared_secret,
            identity_secret.as_deref(),
            token_gid.as_deref(),
            revocation_code.as_deref(),
        )
    } else {
        return Err(MaFileParseError::MissingSteamId);
    };

    if !has_preferred_shared_secret && !is_steam_id64_value(&steam_id) {
        return Err(MaFileParseError::SessionOnlyMissingSteamId);
    }

    let device_id = string_any(&root, &["device_id", "deviceId"])
        .or_else(|| {
            session.and_then(|value| string_any(value, &["DeviceID", "device_id", "deviceId"]))
        })
        .unwrap_or_default();
    let steam_login_secure = session
        .and_then(|value| string_any(value, &["SteamLoginSecure", "steamLoginSecure"]))
        .or_else(|| string_any(&root, &["steamLoginSecure", "steam_login_secure"]));
    let access_token = string_any(
        &root,
        &["access_token", "accessToken", "oauth_token", "OAuthToken"],
    )
    .or_else(|| {
        session.and_then(|value| {
            string_any(
                value,
                &["AccessToken", "access_token", "OAuthToken", "oauth_token"],
            )
        })
    })
    .or_else(|| {
        steam_login_secure.as_deref().and_then(|value| {
            value
                .split_once("||")
                .map(|(_, token)| token.to_string())
                .filter(|token| !token.trim().is_empty())
        })
    });
    let refresh_token = string_any(&root, &["refresh_token", "refreshToken"]).or_else(|| {
        session.and_then(|value| string_any(value, &["RefreshToken", "refresh_token"]))
    });
    let display_name = display_name_override
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(str::to_string)
        .or_else(|| string_any(&root, &["monica_display_name", "monicaDisplayName"]))
        .unwrap_or_else(|| account_name.clone());

    let raw_json = raw_json_for_steam_id(&mut root, plain_json, &steam_id)?;

    Ok(MaFilePayload {
        steam_id,
        account_name,
        display_name,
        device_id,
        shared_secret,
        identity_secret,
        revocation_code,
        token_gid,
        access_token,
        refresh_token,
        steam_login_secure,
        raw_json,
    })
}

fn object_any<'a>(root: &'a Map<String, Value>, keys: &[&str]) -> Option<&'a Map<String, Value>> {
    keys.iter().find_map(|key| root.get(*key)?.as_object())
}

fn string_any(root: &Map<String, Value>, keys: &[&str]) -> Option<String> {
    keys.iter().find_map(|key| value_string(root.get(*key)?))
}

fn value_string(value: &Value) -> Option<String> {
    let result = match value {
        Value::String(value) => value.clone(),
        Value::Number(value) => value.to_string(),
        Value::Bool(value) => value.to_string(),
        _ => return None,
    };
    (!result.trim().is_empty()).then_some(result)
}

fn bool_any(root: &Map<String, Value>, keys: &[&str]) -> Option<bool> {
    keys.iter().find_map(|key| {
        let value = value_string(root.get(*key)?)?;
        match value.to_ascii_lowercase().as_str() {
            "true" | "1" => Some(true),
            "false" | "0" => Some(false),
            _ => None,
        }
    })
}

fn steam_id_any(root: &Map<String, Value>) -> Option<String> {
    string_any(root, STEAM_ID_KEYS).filter(|value| is_steam_id64_value(value))
}

fn local_steam_id_any(root: &Map<String, Value>) -> Option<String> {
    string_any(root, &["monica_local_steamid", "monicaLocalSteamId"])
        .filter(|value| is_monica_local_steam_id(value))
}

fn preferred_shared_secret(root: &Map<String, Value>) -> Option<(String, SharedSecretSource)> {
    if let Some(uri) = string_any(
        root,
        &[
            "uri",
            "Uri",
            "otp_uri",
            "otpUri",
            "otpauth_uri",
            "otpauthUri",
            "steam_uri",
            "steamUri",
            "url",
            "URL",
        ],
    ) {
        if let Some(secret) = extract_shared_secret_from_uri(&uri) {
            return Some(secret);
        }
    }

    let shared_secret = string_any(root, &["shared_secret", "sharedSecret"])?;
    extract_shared_secret_from_uri(&shared_secret)
        .or(Some((shared_secret, SharedSecretSource::StandardField)))
}

fn extract_shared_secret_from_uri(value: &str) -> Option<(String, SharedSecretSource)> {
    let normalized = value.trim();
    if starts_with_ignore_ascii_case(normalized, "steam://") {
        let after_scheme = normalized.split_once("://")?.1;
        let secret = after_scheme
            .split('?')
            .next()
            .unwrap_or_default()
            .split('#')
            .next()
            .unwrap_or_default()
            .trim_matches('/')
            .trim();
        let secret = decode_uri_component(secret);
        return (!secret.trim().is_empty()).then_some((secret, SharedSecretSource::SteamUri));
    }
    if starts_with_ignore_ascii_case(normalized, "otpauth://") {
        let query = normalized.split_once('?')?.1.split('#').next().unwrap_or_default();
        for part in query.split('&') {
            let (key, raw_value) = part.split_once('=').unwrap_or((part, ""));
            if key.eq_ignore_ascii_case("secret") {
                let secret = decode_uri_component(raw_value);
                if !secret.trim().is_empty() {
                    return Some((secret, SharedSecretSource::OtpAuthUri));
                }
                return None;
            }
        }
    }
    None
}

fn starts_with_ignore_ascii_case(value: &str, prefix: &str) -> bool {
    value
        .get(..prefix.len())
        .map(|candidate| candidate.eq_ignore_ascii_case(prefix))
        .unwrap_or(false)
}

fn normalize_steam_shared_secret(
    shared_secret: &str,
    source: SharedSecretSource,
) -> Result<String, MaFileParseError> {
    let compact: String = shared_secret
        .chars()
        .filter(|value| !value.is_whitespace())
        .collect();
    let base64_bytes = decode_base64(&compact);
    let base32_bytes = decode_base32(&compact);
    let selected = match source {
        SharedSecretSource::OtpAuthUri => base32_bytes.or(base64_bytes),
        SharedSecretSource::SteamUri => base64_bytes.or(base32_bytes),
        SharedSecretSource::StandardField => match (base64_bytes, base32_bytes) {
            (Some(value), _) if value.len() == STEAM_SECRET_BYTES => Some(value),
            (_, Some(value)) if value.len() == STEAM_SECRET_BYTES => Some(value),
            (Some(value), _) => Some(value),
            (_, value) => value,
        },
    }
    .ok_or(MaFileParseError::InvalidSharedSecret)?;
    Ok(STANDARD.encode(selected))
}

fn decode_base64(value: &str) -> Option<Vec<u8>> {
    if value.trim().is_empty() {
        return None;
    }
    let mut padded = value.to_string();
    let remainder = padded.len() % 4;
    if remainder != 0 {
        padded.push_str(&"=".repeat(4 - remainder));
    }
    // java.util.Base64 (used by the Kotlin fallback) accepts non-zero unused
    // bits in the final quantum. Keep that compatibility for legacy maFiles.
    let compatible_standard = GeneralPurpose::new(
        &base64::alphabet::STANDARD,
        GeneralPurposeConfig::new().with_decode_allow_trailing_bits(true),
    );
    let compatible_url_safe = GeneralPurpose::new(
        &base64::alphabet::URL_SAFE,
        GeneralPurposeConfig::new().with_decode_allow_trailing_bits(true),
    );
    compatible_standard
        .decode(padded.as_bytes())
        .ok()
        .or_else(|| compatible_url_safe.decode(padded.as_bytes()).ok())
}

fn decode_base32(value: &str) -> Option<Vec<u8>> {
    let normalized: String = value
        .chars()
        .map(|value| value.to_ascii_uppercase())
        .filter(|value| *value != '=' && *value != ' ' && *value != '-')
        .collect();
    if normalized.is_empty()
        || normalized
            .chars()
            .any(|value| !BASE32_ALPHABET.contains(value))
    {
        return None;
    }

    let mut output = Vec::new();
    let mut buffer = 0u32;
    let mut bits_left = 0u32;
    for value in normalized.chars() {
        let index = BASE32_ALPHABET.find(value)? as u32;
        buffer = buffer.wrapping_shl(5) | index;
        bits_left += 5;
        while bits_left >= 8 {
            output.push(((buffer >> (bits_left - 8)) & 0xff) as u8);
            bits_left -= 8;
        }
    }
    (!output.is_empty()).then_some(output)
}

fn decode_uri_component(value: &str) -> String {
    let bytes = value.as_bytes();
    let mut decoded = Vec::with_capacity(bytes.len());
    let mut index = 0usize;
    while index < bytes.len() {
        if bytes[index] == b'%' {
            if index + 2 >= bytes.len() {
                return value.to_string();
            }
            let Some(high) = hex_value(bytes[index + 1]) else {
                return value.to_string();
            };
            let Some(low) = hex_value(bytes[index + 2]) else {
                return value.to_string();
            };
            decoded.push((high << 4) | low);
            index += 3;
        } else {
            decoded.push(bytes[index]);
            index += 1;
        }
    }
    String::from_utf8_lossy(&decoded).into_owned()
}

fn hex_value(value: u8) -> Option<u8> {
    match value {
        b'0'..=b'9' => Some(value - b'0'),
        b'a'..=b'f' => Some(value - b'a' + 10),
        b'A'..=b'F' => Some(value - b'A' + 10),
        _ => None,
    }
}

fn steam_id_from_file_name(file_name: &str) -> Option<String> {
    let normalized = file_name
        .rsplit(|value| value == '/' || value == '\\')
        .next()
        .unwrap_or(file_name);
    let bytes = normalized.as_bytes();
    let prefix = b"7656119";
    for start in 0..bytes.len().saturating_sub(16) {
        let end = start + 17;
        if end > bytes.len() || &bytes[start..start + prefix.len()] != prefix {
            continue;
        }
        if !bytes[start + prefix.len()..end]
            .iter()
            .all(u8::is_ascii_digit)
        {
            continue;
        }
        if start > 0 && bytes[start - 1].is_ascii_digit() {
            continue;
        }
        if end < bytes.len() && bytes[end].is_ascii_digit() {
            continue;
        }
        return Some(normalized[start..end].to_string());
    }
    None
}

fn account_name_from_file_name(file_name: &str) -> Option<String> {
    let before_extension = file_name.rsplit_once('.').map(|(name, _)| name)?;
    (!before_extension.trim().is_empty() && before_extension != file_name)
        .then_some(before_extension.to_string())
}

fn steam_id_from_steam_login_secure(value: &str) -> Option<String> {
    let steam_id = value
        .split_once("||")
        .map(|(steam_id, _)| steam_id)
        .unwrap_or("");
    is_steam_id64_value(steam_id).then_some(steam_id.to_string())
}

fn is_steam_id64_value(value: &str) -> bool {
    value.len() == 17
        && value.starts_with("7656119")
        && value.as_bytes()[7..].iter().all(u8::is_ascii_digit)
}

fn is_monica_local_steam_id(value: &str) -> bool {
    let Some(suffix) = value.strip_prefix(MONICA_LOCAL_STEAM_ID_PREFIX) else {
        return false;
    };
    (16..=64).contains(&suffix.len())
        && suffix
            .bytes()
            .all(|value| value.is_ascii_digit() || (b'a'..=b'f').contains(&value))
}

fn normalized_steam_id_override(value: Option<&str>) -> Result<Option<String>, MaFileParseError> {
    let Some(value) = value.map(str::trim).filter(|value| !value.is_empty()) else {
        return Ok(None);
    };
    if is_steam_id64_value(value) {
        return Ok(Some(value.to_string()));
    }
    if !(1..=10).contains(&value.len()) || !value.bytes().all(|value| value.is_ascii_digit()) {
        return Err(MaFileParseError::InvalidSteamIdOverride);
    }
    let account_id = value
        .parse::<u64>()
        .ok()
        .filter(|value| (1..=STEAM_ACCOUNT_ID32_MAX).contains(value))
        .ok_or(MaFileParseError::InvalidSteamIdOverride)?;
    Ok(Some((STEAM_ID64_BASE + account_id).to_string()))
}

fn raw_json_for_steam_id(
    root: &mut Map<String, Value>,
    original_json: &str,
    steam_id: &str,
) -> Result<String, MaFileParseError> {
    let has_real_steam_id = is_steam_id64_value(steam_id);
    let already_has_real_steam_id = steam_id_any(root).is_some();
    let has_missing_marker = root.contains_key("monica_missing_steamid")
        || root.contains_key("monicaMissingSteamId")
        || root.contains_key("monica_local_steamid")
        || root.contains_key("monicaLocalSteamId");
    if has_real_steam_id && already_has_real_steam_id && !has_missing_marker {
        return Ok(original_json.to_string());
    }

    if has_real_steam_id {
        remove_missing_steam_id_markers(root);
        if !already_has_real_steam_id {
            root.insert("steamid".to_string(), Value::String(steam_id.to_string()));
        }
    } else {
        remove_steam_id_fields(root);
        root.insert("monica_missing_steamid".to_string(), Value::Bool(true));
        root.insert(
            "monica_local_steamid".to_string(),
            Value::String(steam_id.to_string()),
        );
    }
    serde_json::to_string(root).map_err(|_| MaFileParseError::InvalidJson)
}

fn remove_missing_steam_id_markers(root: &mut Map<String, Value>) {
    for key in [
        "monica_missing_steamid",
        "monicaMissingSteamId",
        "monica_local_steamid",
        "monicaLocalSteamId",
    ] {
        root.remove(key);
    }
}

fn remove_steam_id_fields(root: &mut Map<String, Value>) {
    for key in STEAM_ID_KEYS {
        root.remove(*key);
    }
    remove_missing_steam_id_markers(root);

    let mut session = root
        .get("Session")
        .and_then(Value::as_object)
        .cloned()
        .or_else(|| root.get("session").and_then(Value::as_object).cloned());
    if let Some(ref mut session) = session {
        for key in STEAM_ID_KEYS {
            session.remove(*key);
        }
    }
    if let Some(session) = session {
        if root.contains_key("Session") {
            root.insert("Session".to_string(), Value::Object(session));
        } else {
            root.insert("session".to_string(), Value::Object(session));
        }
    }
}

fn generate_local_steam_id(
    account_name: &str,
    shared_secret: &str,
    identity_secret: Option<&str>,
    token_gid: Option<&str>,
    revocation_code: Option<&str>,
) -> String {
    let material = format!(
        "{}|{}|{}|{}|{}",
        account_name,
        shared_secret,
        identity_secret.unwrap_or_default(),
        token_gid.unwrap_or_default(),
        revocation_code.unwrap_or_default()
    );
    let digest = Sha256::digest(material.as_bytes());
    let mut hex = String::with_capacity(digest.len() * 2);
    for value in digest {
        use std::fmt::Write as _;
        let _ = write!(hex, "{value:02x}");
    }
    format!("{MONICA_LOCAL_STEAM_ID_PREFIX}{}", &hex[..24])
}

#[cfg(test)]
mod tests {
    use super::*;

    const SECRET_B64: &str = "AAECAwQFBgcICQoLDA0ODxAREhM=";
    const SECRET_B32: &str = "AAAQEAYEAUDAOCAJBIFQYDIOB4IBCEQT";

    #[test]
    fn parses_standard_mafile_aliases_and_nested_session() {
        let input = format!(
            r#"{{"accountName":"alice","sharedSecret":"{SECRET_B64}","identity_secret":"identity","Session":{{"SteamID":"76561198000000000","DeviceID":"android:abc","AccessToken":"access","RefreshToken":"refresh"}}}}"#
        );
        let payload = parse_ma_file_json(&input, None, None, None, false).unwrap();
        assert_eq!(payload.steam_id, "76561198000000000");
        assert_eq!(payload.account_name, "alice");
        assert_eq!(payload.device_id, "android:abc");
        assert_eq!(payload.shared_secret, SECRET_B64);
        assert_eq!(payload.access_token.as_deref(), Some("access"));
        assert_eq!(payload.refresh_token.as_deref(), Some("refresh"));
    }

    #[test]
    fn otpauth_prefers_base32_and_steam_uri_prefers_base64() {
        let otp = format!(
            r#"{{"steamid":"76561198000000000","uri":"otpauth://totp/Steam?secret={SECRET_B32}"}}"#
        );
        assert_eq!(
            parse_ma_file_json(&otp, None, None, None, false)
                .unwrap()
                .shared_secret,
            SECRET_B64
        );

        let steam = format!(
            r#"{{"steamid":"76561198000000000","uri":"steam://{SECRET_B64}"}}"#
        );
        assert_eq!(
            parse_ma_file_json(&steam, None, None, None, false)
                .unwrap()
                .shared_secret,
            SECRET_B64
        );
    }

    #[test]
    fn session_only_login_allows_empty_secret_only_with_real_steamid() {
        let input = r#"{"steamid":"76561198000000000","monica_session_only_login":true,"access_token":"token"}"#;
        let payload = parse_ma_file_json(input, None, None, None, false).unwrap();
        assert!(payload.shared_secret.is_empty());
        assert_eq!(payload.access_token.as_deref(), Some("token"));

        let missing = r#"{"monica_session_only_login":true,"access_token":"token","monica_missing_steamid":true}"#;
        assert_eq!(
            parse_ma_file_json(missing, None, None, None, true),
            Err(MaFileParseError::SessionOnlyMissingSteamId)
        );
    }

    #[test]
    fn missing_steamid_generates_stable_local_id_and_enriches_json() {
        let input = format!(r#"{{"account_name":"alice","shared_secret":"{SECRET_B64}"}}"#);
        let first = parse_ma_file_json(&input, None, None, None, true).unwrap();
        let second = parse_ma_file_json(&input, None, None, None, true).unwrap();
        assert_eq!(first.steam_id, second.steam_id);
        assert!(first.steam_id.starts_with(MONICA_LOCAL_STEAM_ID_PREFIX));
        let raw: Value = serde_json::from_str(&first.raw_json).unwrap();
        assert_eq!(raw["monica_missing_steamid"], Value::Bool(true));
        assert_eq!(raw["monica_local_steamid"], Value::String(first.steam_id));
    }

    #[test]
    fn real_steamid_original_json_is_preserved_byte_for_byte() {
        let input = format!(
            "{{  \"steamid\" : \"76561198000000000\", \"shared_secret\":\"{SECRET_B64}\" }}"
        );
        let payload = parse_ma_file_json(&input, None, None, None, false).unwrap();
        assert_eq!(payload.raw_json, input);
    }

    #[test]
    fn account_id_override_matches_kotlin_conversion() {
        let input = format!(r#"{{"shared_secret":"{SECRET_B64}"}}"#);
        let payload = parse_ma_file_json(&input, None, None, Some("39734272"), false).unwrap();
        assert_eq!(payload.steam_id, "76561198000000000");
    }

    #[test]
    fn standard_base64_accepts_java_trailing_bits_before_base32_fallback() {
        let input = format!(
            r#"{{"steamid":"76561198000000000","shared_secret":"{}B="}}"#,
            "C".repeat(26)
        );
        let payload = parse_ma_file_json(&input, None, None, None, false).unwrap();
        assert_eq!(payload.shared_secret, format!("{}A=", "C".repeat(26)));
    }

    #[test]
    fn filename_steamid_has_precedence_over_filename_account_fallback() {
        let input = format!(r#"{{"shared_secret":"{SECRET_B64}"}}"#);
        let payload = parse_ma_file_json(
            &input,
            Some("alice.76561198000000000.maFile"),
            None,
            None,
            false,
        )
        .unwrap();
        assert_eq!(payload.steam_id, "76561198000000000");
        assert_eq!(payload.account_name, "76561198000000000");
    }
}
