// Compile the approved artwork and ImageGen luminance mask into native icon assets.
// Run from the repository root: swift scripts/export-icons.swift
import AppKit
import ImageIO

let root = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
func url(_ path: String) -> URL { root.appendingPathComponent(path) }
final class Raster {
    let width: Int, height: Int
    let bytes: UnsafeMutablePointer<UInt8>
    let context: CGContext
    init(_ width: Int, _ height: Int) {
        self.width = width; self.height = height
        bytes = .allocate(capacity: width * height * 4)
        bytes.initialize(repeating: 0, count: width * height * 4)
        context = CGContext(data: bytes, width: width, height: height,
            bitsPerComponent: 8, bytesPerRow: width * 4,
            space: CGColorSpace(name: CGColorSpace.sRGB)!,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue)!
    }
    deinit { bytes.deallocate() }
    var image: CGImage { context.makeImage()! }
    func save(_ path: String) throws {
        try FileManager.default.createDirectory(at: url(path).deletingLastPathComponent(), withIntermediateDirectories: true)
        try NSBitmapImageRep(cgImage: image).representation(using: .png, properties: [:])!.write(to: url(path))
    }
}
func render(_ image: CGImage, _ width: Int, _ height: Int, inset: CGFloat = 0) -> Raster {
    let out = Raster(width, height)
    out.context.interpolationQuality = .high
    let scale = min((CGFloat(width) - inset * 2) / CGFloat(image.width),
                    (CGFloat(height) - inset * 2) / CGFloat(image.height))
    let w = CGFloat(image.width) * scale, h = CGFloat(image.height) * scale
    out.context.draw(image, in: CGRect(x: (CGFloat(width) - w) / 2,
        y: (CGFloat(height) - h) / 2, width: w, height: h))
    return out
}
func load(_ path: String) -> Raster {
    let source = CGImageSourceCreateWithURL(url(path) as CFURL, nil)!
    let image = CGImageSourceCreateImageAtIndex(source, 0, nil)!
    return render(image, image.width, image.height)
}

let tile = load("design/icon/Unisono-approved.png")
let w = tile.width, h = tile.height
var level = [UInt8](repeating: 0, count: w * h)
for p in level.indices { level[p] = max(tile.bytes[p * 4], tile.bytes[p * 4 + 1], tile.bytes[p * 4 + 2]) }
// Encode only the source's edge-connected black exterior matte as transparency.
var exterior = [Bool](repeating: false, count: w * h)
var queue = [Int]()
func enqueue(_ p: Int) {
    if !exterior[p] && level[p] <= 12 { exterior[p] = true; queue.append(p) }
}
for x in 0..<w { enqueue(x); enqueue((h - 1) * w + x) }
for y in 0..<h { enqueue(y * w); enqueue(y * w + w - 1) }
var cursor = 0
while cursor < queue.count {
    let p = queue[cursor]; cursor += 1
    let x = p % w, y = p / w
    if x > 0 { enqueue(p - 1) }; if x < w - 1 { enqueue(p + 1) }
    if y > 0 { enqueue(p - w) }; if y < h - 1 { enqueue(p + w) }
}
for p in queue {
    let alpha = max(0, min(1, (Double(level[p]) - 2) / 10))
    for c in 0..<3 { tile.bytes[p * 4 + c] = UInt8(Double(tile.bytes[p * 4 + c]) * alpha) }
    tile.bytes[p * 4 + 3] = UInt8(alpha * 255)
}

let mask = load("design/icon/Unisono-symbol-mask.png")
let white = Raster(mask.width, mask.height), black = Raster(mask.width, mask.height), mint = Raster(mask.width, mask.height)
var minX = mask.width, minY = mask.height, maxX = 0, maxY = 0
for y in 0..<mask.height {
    for x in 0..<mask.width {
        let p = (y * mask.width + x) * 4
        let value = Double(Int(mask.bytes[p]) + Int(mask.bytes[p + 1]) + Int(mask.bytes[p + 2])) / 765
        // Convert the generated grayscale mask into alpha, excluding near-black noise.
        let alpha = max(0, min(1, (value - 0.08) / 0.84))
        let a = UInt8(alpha * 255)
        for c in 0..<3 { white.bytes[p + c] = a }
        white.bytes[p + 3] = a; black.bytes[p + 3] = a
        mint.bytes[p] = UInt8(Double(a) * 0.49)
        mint.bytes[p + 1] = UInt8(Double(a) * 0.81)
        mint.bytes[p + 2] = UInt8(Double(a) * 0.62)
        mint.bytes[p + 3] = a
        if alpha > 0.5 { minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y) }
    }
}
let bounds = CGRect(x: minX - 2, y: minY - 2, width: maxX - minX + 5, height: maxY - minY + 5)
let whiteMark = white.image.cropping(to: bounds)!, blackMark = black.image.cropping(to: bounds)!, mintMark = mint.image.cropping(to: bounds)!
try render(tile.image, 1024, 1024, inset: 64).save("design/icon/Unisono-1024.png")
try render(mintMark, 512, 512, inset: 24).save("design/icon/Unisono-symbol.png")
for size in [16, 32, 128, 256, 512] {
    for scale in [1, 2] {
        let suffix = scale == 2 ? "@2x" : "", pixels = size * scale
        try render(tile.image, pixels, pixels, inset: CGFloat(pixels) / 16).save(
            "build/icons/Unisono.iconset/icon_\(size)x\(size)\(suffix).png")
    }
}
for scale in [1, 2, 3] {
    let suffix = scale == 1 ? "" : "@\(scale)x"
    try render(blackMark, 20 * scale, 18 * scale, inset: CGFloat(scale)).save("mac/Resources/MenuBarTemplate\(suffix).png")
}
let densities: [(String, CGFloat)] = [("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4)]
for (density, scale) in densities {
    let res = "android/app/src/main/res"
    let legacy = Int(48 * scale), adaptive = Int(108 * scale), notification = Int(24 * scale)
    try render(tile.image, legacy, legacy).save("\(res)/mipmap-\(density)/ic_launcher.png")
    // Preserve the approved satin artwork. At 82dp its glyph spans about 62dp;
    // the tile extends beyond the launcher's normal mask to avoid a nested border.
    try render(tile.image, adaptive, adaptive, inset: 13 * scale).save("\(res)/drawable-\(density)/ic_launcher_foreground.png")
    try render(whiteMark, adaptive, adaptive, inset: 23 * scale).save("\(res)/drawable-\(density)/ic_unisono_monochrome.png")
    try render(whiteMark, notification, notification, inset: 2 * scale).save("\(res)/drawable-\(density)/ic_notification.png")
}
print("Exported Mac iconset, menu templates, and Android icon densities.")
