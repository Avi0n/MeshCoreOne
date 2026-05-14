import Foundation

public extension ChatCoordinator {

    /// Rebuild `renderState` from a snapshot of inputs already assembled on
    /// the main actor. Runs the per-message builder loop off the main actor
    /// inside a `Task { @concurrent }` hop, then applies the result on main
    /// when the captured `renderStateID` still matches.
    ///
    /// The caller supplies the `(MessageDTO, MessageBuildInputs)` pairs so
    /// per-message inputs that depend on `ChatViewModel` state (preview
    /// states, cached URLs, image decode results) can be assembled on the
    /// main actor where that state lives. `MessageFragmentBuilder.makeItem`
    /// is pure over `Sendable` inputs, so the off-actor portion captures
    /// only `Sendable` values.
    ///
    /// `postApply` runs on the main actor after a successful apply. The
    /// view model uses it to kick off URL detection and legacy preview
    /// decoding without needing to know about the off-main hop.
    func rebuildItems(
        inputs: [(MessageDTO, MessageBuildInputs)],
        envInputs: EnvInputs,
        postApply: (@MainActor () -> Void)? = nil
    ) {
        // Bump the generation before capturing it so two back-to-back
        // rebuilds (link-preview load + URL detection, for example) cannot
        // share the same `capturedID`. Without this, an older build that
        // finished after a newer one would still pass `setRenderState`'s
        // guard and clobber state. Last-scheduled-wins.
        renderStateID &+= 1
        let capturedID = renderStateID
        let snapshot = inputs

        // Run off the MainActor; `MessageFragmentBuilder` is pure over
        // Sendable inputs so the snapshot is safe to consume from a
        // detached task.
        buildItemsTask?.cancel()
        buildItemsTask = Task(priority: .userInitiated) { @concurrent [weak self] in
            var built: [MessageItem] = []
            built.reserveCapacity(snapshot.count)
            for (message, perMessageInputs) in snapshot {
                if Task.isCancelled { return }
                built.append(MessageFragmentBuilder.makeItem(
                    for: message,
                    inputs: perMessageInputs,
                    envInputs: envInputs
                ))
            }

            await MainActor.run {
                guard let self else { return }
                guard !Task.isCancelled else { return }
                let new = self.renderState.with(items: built, itemIndexByID: built.indexByID())
                let applied = self.setRenderState(new, capturedID: capturedID)
                if !applied {
                    // A fresher mutation landed mid-flight. Notify the bound
                    // view model so it can reassemble per-message inputs and
                    // call rebuildItems again.
                    self.renderStateInvalidated?()
                    return
                }
                postApply?()
            }
        }
    }
}
