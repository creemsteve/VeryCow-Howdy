//! Carbon Gray 100 / White tokens adapted to native egui widgets.
use eframe::egui::{self, Color32, FontId, Margin, RichText, Rounding, Stroke, TextStyle};

pub const BLUE: Color32 = Color32::from_rgb(15, 98, 254);
pub const SHELL: Color32 = Color32::from_rgb(22, 22, 22);
pub const WHITE: Color32 = Color32::from_rgb(244, 244, 244);
pub const LAYER_01: Color32 = Color32::from_rgb(38, 38, 38);
pub const LAYER_02: Color32 = Color32::from_rgb(57, 57, 57);
pub const LAYER_03: Color32 = Color32::from_rgb(82, 82, 82);
pub const TEXT_SECONDARY: Color32 = Color32::from_rgb(198, 198, 198);
pub const BORDER_SUBTLE: Color32 = Color32::from_rgb(57, 57, 57);
pub const FOCUS: Color32 = Color32::WHITE;

pub fn layer(ui: &egui::Ui) -> Color32 {
    if ui.visuals().dark_mode {
        LAYER_01
    } else {
        Color32::from_rgb(244, 244, 244)
    }
}

pub fn apply(ctx: &egui::Context, dark: bool) {
    let mut style = (*ctx.style()).clone();
    let (background, surface, hover, active, text, border, link) = if dark {
        (
            SHELL,
            LAYER_01,
            Color32::from_rgb(53, 53, 53),
            Color32::from_rgb(69, 69, 69),
            WHITE,
            Color32::from_rgb(82, 82, 82),
            Color32::from_rgb(120, 169, 255),
        )
    } else {
        (
            Color32::WHITE,
            WHITE,
            Color32::from_rgb(232, 232, 232),
            Color32::from_rgb(224, 224, 224),
            SHELL,
            Color32::from_rgb(198, 198, 198),
            BLUE,
        )
    };
    let mut visuals = if dark {
        egui::Visuals::dark()
    } else {
        egui::Visuals::light()
    };
    visuals.panel_fill = background;
    visuals.window_fill = surface;
    visuals.extreme_bg_color = surface;
    visuals.faint_bg_color = surface;
    visuals.code_bg_color = surface;
    visuals.override_text_color = None;
    visuals.hyperlink_color = link;
    visuals.warn_fg_color = Color32::from_rgb(241, 194, 27);
    visuals.error_fg_color = if dark {
        Color32::from_rgb(255, 131, 137)
    } else {
        Color32::from_rgb(218, 30, 40)
    };
    visuals.selection.bg_fill = if dark {
        Color32::from_rgb(0, 67, 206)
    } else {
        Color32::from_rgb(208, 226, 255)
    };
    visuals.selection.stroke = Stroke::new(1_f32, text);
    visuals.window_rounding = Rounding::ZERO;
    visuals.menu_rounding = Rounding::ZERO;
    visuals.window_stroke = Stroke::new(1_f32, border);
    visuals.window_shadow = egui::epaint::Shadow::NONE;
    for widget in [
        &mut visuals.widgets.noninteractive,
        &mut visuals.widgets.inactive,
        &mut visuals.widgets.hovered,
        &mut visuals.widgets.active,
        &mut visuals.widgets.open,
    ] {
        widget.rounding = Rounding::ZERO;
        widget.expansion = 0.;
        widget.bg_fill = surface;
        widget.weak_bg_fill = surface;
        widget.bg_stroke = Stroke::new(1_f32, border);
        widget.fg_stroke = Stroke::new(1_f32, text);
    }
    visuals.widgets.noninteractive.fg_stroke.color = text;
    visuals.widgets.noninteractive.bg_stroke.color = if dark { BORDER_SUBTLE } else { border };
    visuals.collapsing_header_frame = true;
    visuals.widgets.hovered.bg_fill = hover;
    visuals.widgets.hovered.weak_bg_fill = hover;
    visuals.widgets.hovered.bg_stroke = Stroke::new(1_f32, border);
    visuals.widgets.active.bg_fill = active;
    visuals.widgets.active.weak_bg_fill = active;
    visuals.widgets.active.bg_stroke = Stroke::new(2_f32, if dark { FOCUS } else { BLUE });
    visuals.widgets.open.bg_fill = active;
    visuals.widgets.open.weak_bg_fill = active;
    visuals.text_cursor.stroke = Stroke::new(2_f32, link);
    style.visuals = visuals;
    style.spacing.item_spacing = egui::vec2(8., 8.);
    style.spacing.button_padding = egui::vec2(16., 10.);
    style.spacing.interact_size = egui::vec2(40., 40.);
    style.spacing.text_edit_width = 360.;
    style.spacing.window_margin = Margin::same(16.);
    style.spacing.indent = 16.;
    style
        .text_styles
        .insert(TextStyle::Heading, FontId::proportional(28.));
    style
        .text_styles
        .insert(TextStyle::Body, FontId::proportional(14.));
    style
        .text_styles
        .insert(TextStyle::Button, FontId::proportional(14.));
    style
        .text_styles
        .insert(TextStyle::Small, FontId::proportional(12.));
    style
        .text_styles
        .insert(TextStyle::Monospace, FontId::monospace(12.));
    ctx.set_style(style);
}

pub fn content(ctx: &egui::Context) -> egui::Frame {
    egui::Frame::none()
        .fill(ctx.style().visuals.panel_fill)
        .inner_margin(Margin::same(24.))
}

pub fn primary(label: &str) -> impl egui::Widget + '_ {
    move |ui: &mut egui::Ui| {
        let response = ui
            .scope(|ui| {
                let enabled = ui.is_enabled();
                let widgets = &mut ui.visuals_mut().widgets;
                widgets.inactive.weak_bg_fill = if enabled { BLUE } else { LAYER_02 };
                widgets.hovered.weak_bg_fill = Color32::from_rgb(3, 83, 233);
                widgets.active.weak_bg_fill = Color32::from_rgb(0, 45, 156);
                widgets.inactive.bg_stroke = Stroke::NONE;
                widgets.hovered.bg_stroke = Stroke::NONE;
                widgets.active.bg_stroke = Stroke::NONE;
                ui.add(
                    egui::Button::new(RichText::new(label).color(if ui.is_enabled() {
                        Color32::WHITE
                    } else {
                        Color32::from_rgb(141, 141, 141)
                    }))
                    .min_size(egui::vec2(128., 40.)),
                )
            })
            .inner;
        if response.has_focus() {
            ui.painter().rect_stroke(
                response.rect.shrink(3.),
                0.,
                Stroke::new(2_f32, Color32::WHITE),
            );
        }
        response
    }
}

pub fn input(ui: &mut egui::Ui, value: &mut String) -> egui::Response {
    ui.add(
        egui::TextEdit::singleline(value)
            .margin(Margin::symmetric(16., 10.))
            .min_size(egui::vec2(0., 40.))
            .desired_width(ui.available_width().min(480.)),
    )
}

pub fn textarea(ui: &mut egui::Ui, value: &mut String) -> egui::Response {
    ui.add(
        egui::TextEdit::multiline(value)
            .margin(Margin::symmetric(16., 10.))
            .desired_width(ui.available_width().min(600.))
            .desired_rows(3),
    )
}

pub fn nav(ui: &mut egui::Ui, selected: bool, label: &str) -> egui::Response {
    let response = ui
        .scope(|ui| {
            ui.visuals_mut().selection.bg_fill = if ui.visuals().dark_mode {
                LAYER_02
            } else {
                Color32::from_rgb(224, 224, 224)
            };
            ui.visuals_mut().selection.stroke = Stroke::NONE;
            let hover = if ui.visuals().dark_mode {
                Color32::from_rgb(71, 71, 71)
            } else {
                Color32::from_rgb(232, 232, 232)
            };
            ui.visuals_mut().widgets.inactive.weak_bg_fill = Color32::TRANSPARENT;
            ui.visuals_mut().widgets.inactive.bg_stroke = Stroke::NONE;
            ui.visuals_mut().widgets.hovered.weak_bg_fill = hover;
            ui.visuals_mut().widgets.hovered.bg_stroke = Stroke::NONE;
            ui.visuals_mut().widgets.active.bg_stroke = Stroke::NONE;
            ui.spacing_mut().interact_size.y = 32.;
            ui.spacing_mut().button_padding.y = 6.;
            ui.add(
                egui::Button::new(label)
                    .min_size(egui::vec2(ui.available_width(), 32.))
                    .truncate()
                    .selected(selected)
                    .frame(true),
            )
        })
        .inner;
    if selected {
        ui.painter().rect_filled(
            egui::Rect::from_min_size(response.rect.min, egui::vec2(3., response.rect.height())),
            0.,
            BLUE,
        );
    }
    if response.has_focus() {
        ui.painter().rect_stroke(
            response.rect.shrink(1.),
            0.,
            Stroke::new(2_f32, ui.visuals().text_color()),
        );
    }
    response.on_hover_text(label)
}

pub fn eyebrow(ui: &mut egui::Ui, label: &str) {
    ui.add_space(8.);
    ui.label(RichText::new(label).small().color(secondary(ui)));
}

pub fn title(ui: &mut egui::Ui, title: &str, description: &str) {
    ui.heading(title);
    ui.label(RichText::new(description).color(secondary(ui)));
    ui.add_space(16.);
    ui.separator();
    ui.add_space(8.);
}

pub fn secondary(ui: &egui::Ui) -> Color32 {
    if ui.visuals().dark_mode {
        TEXT_SECONDARY
    } else {
        Color32::from_rgb(82, 82, 82)
    }
}
