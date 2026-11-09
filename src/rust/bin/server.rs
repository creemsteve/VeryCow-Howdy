#![cfg_attr(target_os = "windows", windows_subsystem = "windows")]
fn main() {
    if let Err(error) = vch_core::desktop::launch(vch_core::desktop::Mode::Server) {
        rfd::MessageDialog::new()
            .set_title("VCH / Server")
            .set_description(error.to_string())
            .set_level(rfd::MessageLevel::Error)
            .show();
    }
}
