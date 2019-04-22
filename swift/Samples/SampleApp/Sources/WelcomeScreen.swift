import Workflow
import WorkflowUI


struct WelcomeScreen: Screen {
    var name: String
    var onNameChanged: (String) -> Void
    var onLoginTapped: () -> Void
}


final class WelcomeViewController: ScreenViewController<WelcomeScreen> {
    let welcomeLabel: UILabel
    let nameField: UITextField
    let button: UIButton

    required init(screen: WelcomeScreen, viewRegistry: ViewRegistry) {
        welcomeLabel = UILabel(frame: .zero)
        nameField = UITextField(frame: .zero)
        button = UIButton(frame: .zero)
        super.init(screen: screen, viewRegistry: viewRegistry)

        update(with: screen)
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        welcomeLabel.text = "Welcome! Please Enter Your Name"
        welcomeLabel.textAlignment = .center

        nameField.backgroundColor = UIColor(white: 0.92, alpha: 1.0)
        nameField.addTarget(self, action: #selector(textDidChange(sender:)), for: .editingChanged)

        button.backgroundColor = UIColor(red: 41/255, green: 150/255, blue: 204/255, alpha: 1.0)
        button.setTitle("Login", for: .normal)
        button.addTarget(self, action: #selector(buttonTapped(sender:)), for: .touchUpInside)

        view.addSubview(welcomeLabel)
        view.addSubview(nameField)
        view.addSubview(button)
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()

        let inset: CGFloat = 12.0
        let height: CGFloat = 44.0
        var yOffset = (view.bounds.size.height - (2 * height + inset)) / 2.0

        welcomeLabel.frame = CGRect(
            x: view.bounds.origin.x,
            y: view.bounds.origin.y,
            width: view.bounds.size.width,
            height: yOffset)

        nameField.frame = CGRect(
            x: view.bounds.origin.x,
            y: yOffset,
            width: view.bounds.size.width,
            height: height)
            .insetBy(dx: inset, dy: 0.0)

        yOffset += height + inset
        button.frame = CGRect(
            x: view.bounds.origin.x,
            y: yOffset,
            width: view.bounds.size.width,
            height: height)
            .insetBy(dx: inset, dy: 0.0)
    }

    override func screenDidChange(from previousScreen: WelcomeScreen) {
        update(with: screen)
    }

    private func update(with screen: WelcomeScreen) {
        nameField.text = screen.name
    }

    @objc private func textDidChange(sender: UITextField) {
        guard let text = sender.text else {
            return
        }
        screen.onNameChanged(text)
    }

    @objc private func buttonTapped(sender: UIButton) {
        screen.onLoginTapped()
    }
}


extension ViewRegistry {

    public mutating func registerWelcomeScreen() {
        self.register(screenViewControllerType: WelcomeViewController.self)
    }

}
