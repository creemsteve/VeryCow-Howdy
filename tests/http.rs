use std::io::Cursor;
use vch_core::http::*;
#[test]
fn response_framing() {
    for bytes in [b"HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello".as_slice(),b"HTTP/1.0 200 OK\r\n\r\nhello".as_slice(),b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nhe\r\n3;ext=yes\r\nllo\r\n0\r\nX-Trailer: yes\r\n\r\n".as_slice()]{let r=read_reply(&mut Cursor::new(bytes)).unwrap();assert_eq!(r.status,200);assert_eq!(r.body,b"hello");}
    for bytes in [
        "bad\r\n\r\n",
        "HTTP/1.1 200 OK\n\n",
        "HTTP/1.1 200 OK\r\nContent-Length: 5\r\nContent-Length: 5\r\n\r\nhello",
        "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nContent-Length: 5\r\n\r\n",
        "HTTP/1.1 200 OK\r\nContent-Length: 999999999\r\n\r\n",
        "HTTP/1.1 200 OK\r\nContent-Length: -1\r\n\r\n",
        "HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip\r\n\r\n",
        "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\ng\r\n",
        "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabcXX",
        "HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nshort",
        "HTTP/1.1 200 OK\r\nno colon\r\n\r\n",
        "HTTP/1.1 200 OK\r\nBad Name: x\r\n\r\n",
    ] {
        assert!(read_reply(&mut Cursor::new(bytes)).is_err(), "{bytes}");
    }
    let long = format!("HTTP/1.1 200 OK\r\nX: {}\r\n\r\n", "a".repeat(9000));
    assert!(read_reply(&mut Cursor::new(long)).is_err());
    let mut output = Vec::new();
    write_reply(&mut output, 429, b"error", &[("Retry-After", "10".into())]).unwrap();
    let r = read_reply(&mut Cursor::new(output)).unwrap();
    assert_eq!(r.status, 429);
    assert_eq!(r.headers["retry-after"], "10");
    assert!(write_reply(&mut Vec::new(), 200, b"", &[("X", "bad\r\nheader".into())]).is_err());
}
