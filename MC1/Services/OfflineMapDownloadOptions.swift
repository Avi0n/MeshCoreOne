struct OfflineMapDownloadOptions {
  let baseMaximumZoom: Int
  let topographyMaximumZoom: Int?

  var requests: [(layer: OfflineMapLayer, maximumZoom: Int)] {
    var requests: [(layer: OfflineMapLayer, maximumZoom: Int)] = [(.base, baseMaximumZoom)]
    if let topographyMaximumZoom {
      requests.append((.topo, topographyMaximumZoom))
    }
    return requests
  }
}
