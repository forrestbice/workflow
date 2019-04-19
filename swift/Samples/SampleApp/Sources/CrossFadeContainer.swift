import Workflow
import WorkflowUI


struct CrossFadeScreen: Screen {
    private var screenType: Any.Type
    var screen: AnyScreen
    var key: String

    init<ScreenType: Screen>(_ screen: ScreenType, key: String = "") {
        self.screenType = ScreenType.self

        self.screen = AnyScreen(screen)
        self.key = key
    }

    fileprivate func isEquivalent(to otherScreen: CrossFadeScreen) -> Bool {
        return (self.screenType == otherScreen.screenType) && (self.key == otherScreen.key)
    }
}


final class CrossFadeContainerViewController: ScreenViewController<CrossFadeScreen> {
    var childViewController: ScreenViewController<AnyScreen>

    required init(screen: CrossFadeScreen, viewRegistry: ViewRegistry) {
        childViewController = viewRegistry.provideView(for: screen.screen)
        super.init(screen: screen, viewRegistry: viewRegistry)
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        addChild(childViewController)
        view.addSubview(childViewController.view)
        childViewController.didMove(toParent: self)
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()

        childViewController.view.frame = view.bounds
    }

    override func screenDidChange(from previousScreen: CrossFadeScreen) {
        if screen.isEquivalent(to: previousScreen) {
            // The type and key is the same, just update the screen.
            childViewController.update(screen: screen.screen)
        } else {
            // The new screen is different than the previous. Animate the transition.
            let oldChild = childViewController
            childViewController = viewRegistry.provideView(for: screen.screen)
            addChild(childViewController)
            view.addSubview(childViewController.view)
            UIView.transition(
                from: oldChild.view,
                to: childViewController.view,
                duration: 0.72,
                options: .transitionCrossDissolve,
                completion: { [childViewController] completed in
                    childViewController.didMove(toParent: self)

                    oldChild.willMove(toParent: nil)
                    oldChild.view.removeFromSuperview()
                    oldChild.removeFromParent()
                })
        }
    }

}


extension ViewRegistry {

    public mutating func registerCrossFadeContainer() {
        self.register(screenViewControllerType: CrossFadeContainerViewController.self)
    }

}
