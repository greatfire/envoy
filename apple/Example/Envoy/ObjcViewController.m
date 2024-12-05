//
//  ObjcViewController.m
//  Envoy
//
//  Created by Benjamin Erhart on 10.07.24.
//  Copyright © 2024 GreatFire. Licensed under Apache-2.0.
//

#import "ObjcViewController.h"
#import <GreatfireEnvoy/GreatfireEnvoy-Swift.h>
#import "Envoy_Example-Swift.h"
#import <OSLog/OSLog.h>

@interface ObjcViewController ()

@property (weak, nonatomic) IBOutlet UITextField *addressTf;
@property (weak, nonatomic) IBOutlet UIView *busyView;
@property (nonatomic) EnvoyWebView *webView;

@end

@implementation ObjcViewController
{
    os_log_t log;
}

- (void)viewDidLoad {
    [super viewDidLoad];

    NSString* subsystem = [NSBundle bundleForClass:self.class].bundleIdentifier;
    if (!subsystem)
    {
        subsystem = NSStringFromClass(self.class);
    }

    NSString *category = NSStringFromClass(self.class);

    log = os_log_create([subsystem cStringUsingEncoding:NSUTF8StringEncoding], [category cStringUsingEncoding:NSUTF8StringEncoding]);

    self.addressTf.text = @"https://www.wikipedia.org";

    self.busyView.layer.zPosition = 1000;

    Envoy.ptLogging = YES;
    os_log_debug(log, "ptStateDir=%@", Envoy.ptStateDir.path);

    NSArray<Proxy *>* proxies = [Proxy fetch];
    os_log_debug(log, "proxies=%@", proxies);

    NSMutableArray<NSURL *>* urls = [NSMutableArray new];

    for (Proxy* proxy in proxies) {
        [urls addObject:proxy.url];
    }

    [Envoy.shared
     startWithUrls:urls
     test:Test.default_
     testDirect:urls.count == 0
     completionHandler:^{
        dispatch_async(dispatch_get_main_queue(), ^{
            os_log_debug(self->log, "selected proxy: %@", Envoy.shared.proxyDescription);

            [self initWebView];

            self.busyView.hidden = true;

            [self textFieldDidEndEditing:self.addressTf reason:UITextFieldDidEndEditingReasonCommitted];
        });
    }];
}

- (void)viewDidDisappear:(BOOL)animated
{
    [super viewDidDisappear:animated];

    [Envoy.shared stop];
}

- (void)textFieldDidEndEditing:(UITextField *)textField reason:(UITextFieldDidEndEditingReason)reason
{
    if (reason == UITextFieldDidEndEditingReasonCommitted)
    {
        NSString* text = [self.addressTf.text stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];

        if (text.length > 0)
        {
            NSURLComponents* urlc = [[NSURLComponents alloc] initWithString:text];

            NSURL* url = urlc.URL;

            if (url)
            {
                self.addressTf.text = url.absoluteString;

                [self.webView stopLoading];
                [self.webView loadRequest:[[NSURLRequest alloc] initWithURL:url]];
            }
        }
    }
}

- (void)webView:(WKWebView *)webView decidePolicyForNavigationAction:(WKNavigationAction *)navigationAction 
    decisionHandler:(void (^)(WKNavigationActionPolicy))decisionHandler
{
    NSString* text = navigationAction.request.URL.absoluteString;

    if (text.length > 0)
    {
        self.addressTf.text = text;
    }

    decisionHandler(WKNavigationActionPolicyAllow);
}


- (void) initWebView 
{
    self.webView = [[EnvoyWebView alloc] initWithFrame:CGRectZero];
    self.webView.navigationDelegate = self;
    self.webView.translatesAutoresizingMaskIntoConstraints = NO;

    [self.view addSubview:self.webView];

    [self.webView.topAnchor constraintEqualToAnchor:self.addressTf.bottomAnchor constant:8].active = YES;
    [self.webView.leadingAnchor constraintEqualToAnchor: self.view.leadingAnchor].active = YES;
    [self.webView.trailingAnchor constraintEqualToAnchor: self.view.trailingAnchor].active = YES;
    [self.webView.bottomAnchor constraintEqualToAnchor: self.view.bottomAnchor].active = YES;
}


@end
