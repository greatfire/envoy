//
//  ObjcViewController.m
//  Envoy_Example
//
//  Created by Benjamin Erhart on 10.07.24.
//  Copyright © 2024 CocoaPods. All rights reserved.
//

#import "ObjcViewController.h"

@interface ObjcViewController ()

@property (weak, nonatomic) IBOutlet UITextField *addressTf;
@property (weak, nonatomic) IBOutlet UIView *busyView;
@property (nonatomic) EnvoyWebView *webView;

@end

@implementation ObjcViewController

- (void)viewDidLoad {
    [super viewDidLoad];

    self.addressTf.text = @"https://www.wikipedia.org";

    self.busyView.layer.zPosition = 1000;

    Envoy.ptLogging = YES;
    NSLog(@"[%@] ptStateDir=%@", self.class, Envoy.ptStateDir.path);

    [Envoy.shared
     startWithUrls:@[]
     testUrl:[[NSURL alloc] initWithString:@"https://www.google.com/generate_204"]
     testDirect:YES
     completionHandler:^{
        dispatch_async(dispatch_get_main_queue(), ^{
            NSLog(@"[%@] selected proxy: %@", self.class, Envoy.shared.proxyDescription);

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
