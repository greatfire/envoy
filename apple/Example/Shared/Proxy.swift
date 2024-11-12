//
//  Proxy.swift
//  Envoy
//
//  Created by Benjamin Erhart on 12.07.24.
//  Copyright © 2024 GreatFire. Licensed under Apache-2.0.
//

import Foundation
import CryptoKit

/**
 A helper class which reads proxy URLs from a plist file named `proxies.plist` or from an obfuscated file named `proxies`.

 It is recommended to **not check in** that plist file into your version control system, especially, when you develop open source,
 in order to make it harder for attackers to find your proxies.

 You can use `git update-index --skip-worktree proxies.plist` to avoid accidental checkins!

 The plist file is structured as an array of dictionaries, each containing a key `url` and a key `active`.
 The `active` key is a boolean which allows you to easily eanble/disable a URL for testing purposes.

### Usage:

 ```swift
 let proxies = Proxy.fetch()
 await Envoy.shared.start(urls: proxies.map({ $0.url }))
 ```

### Obfuscation

 To make it harder to statically analyze your build artifacts, this class also contains an obfuscation mechanism:

 When this class reads the data from the `proxies.plist` file, it will automatically generate a random
 obfuscation key and symmetrically encrypt the plist file content with that key.
 Both will be printed to the console.

 - Put the BASE64 encoded key in this class' static `Proxy.key` property.
 - Put the BASE64 encoded cyphertext in a text file just called `proxies`.
 - **Remove the `proxies.plist` file from all build targets!**
 - Add the new `proxies` file to the needed build targets instead.
 - Still **do not check in** that file. This is only obfuscation, not robust encryption.
   (Even when it uses high quality symmetric encryption algorithms.)

 */
@objcMembers
class Proxy: NSObject, Decodable {

    private static let key = Data(base64Encoded: "")

    enum CodingKeys: String, CodingKey {
        case _url = "url"
        case active = "active"
    }

    /**
     Read proxy URLs from either a `proxies` plain text file containing a BASE64 encoded, ChaChaPoly encrypted cyphertext,
     or from a `proxies.plist` file, containing an array of dictionaries containing Envoy proxy URLs.

     In order to read from the encrypted file,
     - the key needs to be present in this class' `Proxy.key` property,
     - the `proxies` file needs to be contained in the build containing a BASE64 encoded cypthertext,
     - the key needs to actually be the valid symmetric key.

     Otherwise, this method will fall back to read from a plaintext `proxies.plist` file.

     If that works, it will generate a random key, encrypt the raw plist file and print both to the console.
     That you can use to switch to obfuscated storage of the proxy URLs.

     If everything fails, this method will return an empty array!
     */
    static func fetch() -> [Proxy] {
        if let key = Self.key, !key.isEmpty,
           let url = Bundle.main.url(forResource: "proxies", withExtension: nil),
           let data = try? Data(contentsOf: url),
           let base64 = String(data: data, encoding: .utf8),
           let data = Data(base64Encoded: base64, options: .ignoreUnknownCharacters),
           !data.isEmpty
        {
            do {
                let box = try ChaChaPoly.SealedBox(combined: data)
                let plaintext = try ChaChaPoly.open(box, using: .init(data: key))

                return try PropertyListDecoder().decode(Array<Proxy>.self, from: plaintext)
                    .filter { $0.active && $0.url != nil }
            }
            catch {
                print("[\(String(describing: self))] error=\(error)")
            }
        }

        if let url = Bundle.main.url(forResource: "proxies", withExtension: "plist"),
           let data = try? Data(contentsOf: url)
        {
            if let proxies = (try? PropertyListDecoder().decode(Array<Proxy>.self, from: data))?
                .filter({ $0.active && $0.url != nil })
            {
                do {
                    let key = SymmetricKey(size: .bits256)
                    let box = try ChaChaPoly.seal(data, using: key)

                    let keydata = key.withUnsafeBytes { (pointer) -> Data in
                        return Data(buffer: pointer.bindMemory(to: UInt8.self))
                    }

                    print("[\(String(describing: self))] key=\(keydata.base64EncodedString()), cypthertext=\(box.combined.base64EncodedString())")
                }
                catch {
                    print("[\(String(describing: self))] error=\(error)")
                }

                return proxies
            }
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
