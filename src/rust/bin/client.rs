#![cfg_attr(target_os = "windows", windows_subsystem = "windows")]
fn main() {
    if let Err(error) = vch_core::desktop::launch(vch_core::desktop::Mode::Client) {
        rfd::MessageDialog::new()
            .set_title("VCH / Client")
            .set_description(error.to_string())
            .set_level(rfd::MessageLevel::Error)
            .show();
    }
}
