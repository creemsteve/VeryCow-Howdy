use anyhow::{bail, Result};
use url::Url;
pub const DEFAULT_PORT: u16 = 585;
pub fn normalize(input: &str) -> Result<String> {
    let text = input.trim();
    if text.is_empty() || text.chars().any(char::is_whitespace) || text.contains('\\') {
        bail!("Enter an IP address or domain");
    }
    let address = if text.contains("://") {
        text.to_owned()
    } else if !text.starts_with('[') && text.matches(':').count() > 1 {
        format!("https://[{text}]")
    } else {
        format!("https://{text}")
    };
    let mut url = Url::parse(&address)?;
    if url.scheme() != "https"
        || url.host_str().is_none()
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
        || url.path() != "/"
        || url.port() == Some(0)
    {
        bail!("Enter an HTTPS server address without credentials, path, query or fragment");
    }
    // url normalizes explicit :443 away; preserve that deliberate port.
    let explicit_443 = address.trim_end_matches('/').ends_with(":443");
    let port = url
        .port()
        .unwrap_or(if explicit_443 { 443 } else { DEFAULT_PORT });
    url.set_port(Some(port))
        .map_err(|_| anyhow::anyhow!("Invalid port"))?;
    let host = url.host_str().unwrap();
    Ok(format!("https://{host}:{port}"))
}
pub fn migrate_legacy(input: &str) -> Result<String> {
    let a = normalize(input)?;
    Ok(if a.ends_with(":8443") {
        format!("{}:585", a.trim_end_matches(":8443"))
    } else {
        a
    })
}
