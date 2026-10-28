import SwiftUI
import WebKit
import TorikomiCore

struct GalleryBrowser: UIViewRepresentable {
    let sourceURL: URL
    let resolved: (PhotoAlbum) -> Void
    let failed: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.navigationDelegate = context.coordinator
        if let url = try? PhotoAlbum.requestURL(sourceURL.absoluteString) {
            view.load(URLRequest(url: url, timeoutInterval: 45))
        }
        return view
    }
    func updateUIView(_ view: WKWebView, context: Context) {}
    static func dismantleUIView(_ view: WKWebView, coordinator: Coordinator) {
        coordinator.cancel()
        view.stopLoading()
        view.navigationDelegate = nil
    }

    @MainActor final class Coordinator: NSObject, WKNavigationDelegate {
        private let parent: GalleryBrowser
        private var polling: Task<Void, Never>?
        private var finished = false
        init(_ parent: GalleryBrowser) { self.parent = parent }
        func cancel() { finished = true; polling?.cancel() }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            polling?.cancel()
        }

        func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard navigationAction.targetFrame?.isMainFrame != false else { decisionHandler(.allow); return }
            guard let url = navigationAction.request.url, url.scheme == "https", url.user == nil, url.password == nil,
                  let host = url.host?.lowercased(), ["tiktok.com", "instagram.com"].contains(where: { host == $0 || host.hasSuffix("." + $0) }) else {
                decisionHandler(.cancel); return
            }
            if let normalized = try? PhotoAlbum.requestURL(url.absoluteString), normalized.path != url.path {
                decisionHandler(.cancel)
                webView.load(URLRequest(url: normalized, timeoutInterval: 45))
                return
            }
            decisionHandler(.allow)
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            polling?.cancel()
            polling = Task { @MainActor [weak self, weak webView] in
                guard let self, let webView, !finished else { return }
                do {
                    guard let file = Bundle.main.url(forResource: "gallery", withExtension: "js") else { throw SyncError.invalidData }
                    let script = try String(contentsOf: file, encoding: .utf8)
                    for _ in 0..<90 {
                        try Task.checkCancellation()
                        if let raw = try await webView.evaluateJavaScript(script) as? String {
                            try Task.checkCancellation()
                            let album = try PhotoAlbum.decode(Data(raw.utf8), sourceURL: webView.url ?? parent.sourceURL)
                            guard !finished else { return }
                            finished = true
                            parent.resolved(album)
                            return
                        }
                        try await Task.sleep(for: .seconds(1))
                    }
                    if !finished { parent.failed("formats_empty") }
                } catch is CancellationError {}
                catch { if !finished { parent.failed(error.localizedDescription) } }
            }
        }
        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            if !finished && (error as NSError).code != NSURLErrorCancelled { parent.failed(error.localizedDescription) }
        }
        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            if !finished && (error as NSError).code != NSURLErrorCancelled { parent.failed(error.localizedDescription) }
        }
    }
}
