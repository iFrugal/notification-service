[SUBJECT]
Password Reset Request
[/SUBJECT]
[BODY]
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Password Reset</title>
    <style>
        body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
        .container { max-width: 600px; margin: 0 auto; padding: 20px; }
        .header { background-color: #2196F3; color: white; padding: 20px; text-align: center; }
        .content { padding: 20px; background-color: #f9f9f9; }
        .button { display: inline-block; padding: 12px 24px; background-color: #2196F3; color: white; text-decoration: none; border-radius: 4px; margin: 20px 0; }
        .warning { color: #ff9800; font-size: 0.9em; }
        .footer { padding: 20px; text-align: center; font-size: 0.9em; color: #666; }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h1>Password Reset Request</h1>
        </div>
        <div class="content">
            <p>Hello ${escapeHtml(defaultValue(userName, 'there'))},</p>

            <p>We received a request to reset your password. Click the button below to create a new password:</p>

            <p style="text-align: center;">
                <a href="${resetLink}" class="button">Reset Password</a>
            </p>

            <p class="warning">
                This link will expire in ${defaultValue(expiryMinutes, '60')} minutes.
            </p>

            <p>If you didn't request a password reset, you can safely ignore this email. Your password will remain unchanged.</p>

            <p>For security reasons, this link can only be used once.</p>
        </div>
        <div class="footer">
            <p>If you're having trouble clicking the button, copy and paste the URL below into your browser:</p>
            <p style="word-break: break-all; font-size: 0.8em;">${resetLink}</p>
            <p>&copy; ${.now?string('yyyy')} ${defaultValue(companyName, 'Our Company')}. All rights reserved.</p>
        </div>
    </div>
</body>
</html>
[/BODY]
