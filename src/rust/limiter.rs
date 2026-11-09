use std::collections::HashMap;
#[derive(Default)]
pub struct JoinAttemptLimiter {
    deadlines: HashMap<String, u64>,
}
impl JoinAttemptLimiter {
    pub fn acquire(&mut self, ip: &str, now: u64) -> u32 {
        if let Some(&deadline) = self.deadlines.get(ip) {
            if deadline > now {
                return (deadline - now).div_ceil(1000) as u32;
            }
        }
        self.deadlines.retain(|_, d| *d > now);
        if self.deadlines.len() >= 16384 {
            return 10;
        }
        self.continued(ip, now);
        0
    }
    pub fn continued(&mut self, ip: &str, now: u64) {
        self.deadlines.insert(ip.into(), now.saturating_add(10000));
    }
}
