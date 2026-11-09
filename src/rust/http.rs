use crate::{address, tls::PinVerifier};
use anyhow::{bail, Context, Result};
use std::{
    collections::BTreeMap,
    io::{BufRead, Read, Write},
    net::{IpAddr, SocketAddr, TcpStream, ToSocketAddrs},
    sync::Arc,
    time::Duration,
};
pub const RESPONSE_LIMIT: usize = 24_000_000;
#[derive(Debug)]
pub struct Head {
    pub first: String,
    pub headers: BTreeMap<String, String>,
}
#[derive(Debug)]
pub struct Reply {
    pub status: u16,
    pub headers: BTreeMap<String, String>,
    pub body: Vec<u8>,
}

pub fn line(input: &mut impl BufRead, budget: &mut usize) -> Result<String> {
    let mut out = Vec::new();
    loop {
        if *budget == 0 || out.len() > 8192 {
            bail!("HTTP headers exceed limit");
        }
        let mut c = [0];
        input.read_exact(&mut c)?;
        *budget -= 1;
        if c[0] == b'\r' {
            input.read_exact(&mut c)?;
            if c[0] != b'\n' || *budget == 0 {
                bail!("Invalid HTTP line ending");
            }
            *budget -= 1;
            break;
        }
        if c[0] == b'\n' || c[0] > 127 {
            bail!("Invalid HTTP line");
        }
        out.push(c[0]);
    }
    Ok(String::from_utf8(out)?)
}
pub fn read_head(input: &mut impl BufRead, budget: &mut usize) -> Result<Head> {
    let first = line(input, budget)?;
    let mut headers = BTreeMap::new();
    loop {
        let l = line(input, budget)?;
        if l.is_empty() {
            break;
        }
        let (name, value) = l.split_once(':').context("Invalid HTTP header")?;
        if name.is_empty()
            || !name
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b"!#$%&'*+-.^_`|~".contains(&b))
        {
            bail!("Invalid HTTP header");
        }
        if headers
            .insert(name.to_ascii_lowercase(), value.trim().into())
            .is_some()
        {
            bail!("Duplicate HTTP header");
        }
    }
    Ok(Head { first, headers })
}
pub fn read_body(
    input: &mut impl BufRead,
    headers: &BTreeMap<String, String>,
    limit: usize,
    budget: &mut usize,
) -> Result<Vec<u8>> {
    let transfer = headers.get("transfer-encoding");
    let length = headers.get("content-length");
    if let Some(transfer) = transfer {
        if !transfer.eq_ignore_ascii_case("chunked") || length.is_some() {
            bail!("Unsupported HTTP response framing");
        }
        let mut bytes = Vec::new();
        loop {
            let size = line(input, budget)?;
            let size = size.split(';').next().unwrap();
            if size.is_empty() || size.len() > 8 || !size.bytes().all(|b| b.is_ascii_hexdigit()) {
                bail!("Invalid HTTP chunk size");
            }
            let count = usize::from_str_radix(size, 16)?;
            if count > limit - bytes.len() {
                bail!("Server response exceeds limit");
            }
            if count == 0 {
                while !line(input, budget)?.is_empty() {}
                break;
            }
            let old = bytes.len();
            bytes.resize(old + count, 0);
            input.read_exact(&mut bytes[old..])?;
            let mut end = [0; 2];
            input.read_exact(&mut end)?;
            if end != *b"\r\n" {
                bail!("Invalid HTTP chunk terminator");
            }
        }
        return Ok(bytes);
    }
    if let Some(length) = length {
        if length.is_empty() || length.len() > 10 || !length.bytes().all(|b| b.is_ascii_digit()) {
            bail!("Invalid HTTP content length");
        }
        let count: usize = length.parse()?;
        if count > limit {
            bail!("Server response exceeds limit");
        }
        let mut bytes = vec![0; count];
        input.read_exact(&mut bytes)?;
        return Ok(bytes);
    }
    let mut bytes = Vec::new();
    input.take(limit as u64 + 1).read_to_end(&mut bytes)?;
    if bytes.len() > limit {
        bail!("Server response exceeds limit");
    }
    Ok(bytes)
}
pub fn read_reply(input: &mut impl BufRead) -> Result<Reply> {
    let mut budget = 65536;
    let head = read_head(input, &mut budget)?;
    let parts: Vec<_> = head.first.splitn(3, ' ').collect();
    if parts.len() < 2 || !matches!(parts[0], "HTTP/1.0" | "HTTP/1.1") || parts[1].len() != 3 {
        bail!("Invalid HTTP status");
    }
    let status = parts[1].parse()?;
    let body = read_body(input, &head.headers, RESPONSE_LIMIT, &mut budget)?;
    Ok(Reply {
        status,
        headers: head.headers,
        body,
    })
}
pub fn write_reply(
    output: &mut impl Write,
    status: u16,
    body: &[u8],
    extra: &[(&str, String)],
) -> Result<()> {
    write!(output,"HTTP/1.1 {status} VCH\r\nContent-Length: {}\r\nConnection: close\r\nContent-Type: application/json; charset=utf-8\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n",body.len())?;
    for (k, v) in extra {
        if v.contains(['\r', '\n']) {
            bail!("Invalid response header");
        }
        write!(output, "{k}: {v}\r\n")?;
    }
    output.write_all(b"\r\n")?;
    output.write_all(body)?;
    output.flush()?;
    Ok(())
}
pub fn socks_connect(socket: &mut TcpStream, host: &str, port: u16) -> Result<()> {
    socket.write_all(&[5, 1, 0])?;
    let mut greeting = [0; 2];
    socket.read_exact(&mut greeting)?;
    if greeting != [5, 0] {
        bail!("SOCKS5 listener refused unauthenticated access");
    }
    let host = host.trim_start_matches('[').trim_end_matches(']');
    if host.contains('%') {
        bail!("Scoped IPv6 is unsupported over Tor");
    }
    let mut request = vec![5, 1, 0];
    match host.parse::<IpAddr>() {
        Ok(IpAddr::V6(ip)) => {
            request.push(4);
            request.extend(ip.octets());
        }
        _ => {
            if host.is_empty() || host.len() > 255 || !host.is_ascii() {
                bail!("Invalid Tor hostname");
            }
            request.extend([3, host.len() as u8]);
            request.extend(host.as_bytes());
        }
    }
    request.extend(port.to_be_bytes());
    socket.write_all(&request)?;
    let mut header = [0; 4];
    socket.read_exact(&mut header)?;
    if header[0] != 5 || header[1] != 0 || header[2] != 0 {
        bail!("SOCKS5 refused the destination");
    }
    let length = match header[3] {
        1 => 4,
        4 => 16,
        3 => {
            let mut n = [0];
            socket.read_exact(&mut n)?;
            n[0] as usize
        }
        _ => bail!("Invalid SOCKS5 address type"),
    };
    let mut rest = vec![0; length + 2];
    socket.read_exact(&mut rest)?;
    Ok(())
}
pub fn post(
    address: &str,
    path: &str,
    tor: Option<u16>,
    verifier: &Arc<PinVerifier>,
    body: &[u8],
    token: Option<&str>,
) -> Result<Reply> {
    if path.is_empty() || !path.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-') {
        bail!("Invalid API path");
    }
    if token.is_some_and(|s| s.contains(['\r', '\n'])) {
        bail!("Invalid server session token");
    }
    let normalized = address::normalize(address)?;
    let url = url::Url::parse(&normalized)?;
    let host = url
        .host_str()
        .context("Missing host")?
        .trim_start_matches('[')
        .trim_end_matches(']');
    let port = url.port().unwrap_or(443);
    let timeout = Duration::from_secs(7);
    let mut socket = if let Some(socks) = tor {
        if socks == 0 {
            bail!("Invalid Tor SOCKS port");
        }
        TcpStream::connect_timeout(&SocketAddr::from(([127, 0, 0, 1], socks)), timeout)
            .context("Tor-only connection failed; no direct connection was attempted")?
    } else {
        let addresses: Vec<_> = (host, port).to_socket_addrs()?.collect();
        let mut connected = None;
        for addr in addresses {
            if let Ok(s) = TcpStream::connect_timeout(&addr, timeout) {
                connected = Some(s);
                break;
            }
        }
        connected.context("Cannot connect to server")?
    };
    socket.set_read_timeout(Some(Duration::from_secs(if tor.is_some() {
        60
    } else {
        10
    })))?;
    socket.set_write_timeout(Some(Duration::from_secs(10)))?;
    if tor.is_some() {
        socks_connect(&mut socket, host, port)
            .context("Tor-only connection failed; no direct connection was attempted")?;
    }
    let name = rustls::pki_types::ServerName::try_from(host.to_string())?;
    let conn = rustls::ClientConnection::new(verifier.config()?, name)?;
    let mut stream = rustls::StreamOwned::new(conn, socket);
    write!(stream,"POST /api/{path} HTTP/1.1\r\nHost: {}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n",normalized.trim_start_matches("https://"),body.len())?;
    if let Some(token) = token {
        write!(stream, "Authorization: Bearer {token}\r\n")?;
    }
    stream.write_all(b"\r\n")?;
    stream.write_all(body)?;
    stream.flush()?;
    read_reply(&mut std::io::BufReader::new(stream))
}
