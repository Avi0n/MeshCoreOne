import Foundation

/// Raw `+` in a `meshcore://` query value is a space; `%2B` is a plus.
public enum MeshCoreURIQuery {
  public static func formDecodedValue(_ percentEncoded: String?) -> String {
    guard let percentEncoded else { return "" }
    let plusAsSpace = percentEncoded.replacing("+", with: " ")
    return plusAsSpace.removingPercentEncoding ?? plusAsSpace
  }

  public static func formDecodedValue(named name: String, from items: [URLQueryItem]?) -> String {
    formDecodedValue(items?.first(where: { $0.name == name })?.value)
  }

  /// URLComponents leaves `+` unencoded; rewrite to `%2B` so formDecodedValue keeps a literal plus.
  public static func percentEncodedQueryItems(from items: [URLQueryItem]) -> [URLQueryItem] {
    var components = URLComponents()
    components.queryItems = items
    return (components.percentEncodedQueryItems ?? []).map { item in
      URLQueryItem(name: item.name, value: item.value?.replacing("+", with: "%2B"))
    }
  }
}
