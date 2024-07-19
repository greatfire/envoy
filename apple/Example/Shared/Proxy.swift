//
//  Proxy.swift
//  Envoy
//
//  Created by Benjamin Erhart on 12.07.24.
//  Copyright © 2024 GreatFire. Licensed under Apache-2.0.
//

import Foundation

@objcMembers
class Proxy: NSObject, Decodable {

    enum CodingKeys: String, CodingKey {
        case _url = "url"
        case active = "active"
    }

    static func fetch() -> [Proxy] {
        if let url = Bundle.main.url(forResource: "proxies", withExtension: "plist"),
           let data = try? Data(contentsOf: url)
        {
            return (try? PropertyListDecoder().decode(Array<Proxy>.self, from: data))?.filter({ $0.active && $0.url != nil }) ?? []
        }

        return []
    }


    private let _url: String
    private let active: Bool

    var url: URL! {
        URL(string: _url)
    }

    override var description: String {
        url?.absoluteString ?? "(nil)"
    }
}
