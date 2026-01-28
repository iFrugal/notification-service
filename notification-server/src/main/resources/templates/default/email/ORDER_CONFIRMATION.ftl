[SUBJECT]
Order Confirmation - #${orderId}
[/SUBJECT]
[BODY]
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Order Confirmation</title>
    <style>
        body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
        .container { max-width: 600px; margin: 0 auto; padding: 20px; }
        .header { background-color: #4CAF50; color: white; padding: 20px; text-align: center; }
        .content { padding: 20px; background-color: #f9f9f9; }
        .order-details { margin: 20px 0; }
        table { width: 100%; border-collapse: collapse; }
        th, td { padding: 10px; text-align: left; border-bottom: 1px solid #ddd; }
        th { background-color: #f2f2f2; }
        .total { font-weight: bold; font-size: 1.2em; }
        .footer { padding: 20px; text-align: center; font-size: 0.9em; color: #666; }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h1>Thank You for Your Order!</h1>
        </div>
        <div class="content">
            <p>Hello ${escapeHtml(defaultValue(customerName, 'Valued Customer'))},</p>

            <p>We're pleased to confirm that your order <strong>#${orderId}</strong> has been received and is being processed.</p>

            <div class="order-details">
                <h3>Order Details</h3>
                <table>
                    <tr>
                        <th>Item</th>
                        <th>Quantity</th>
                        <th>Price</th>
                    </tr>
                    <#if items??>
                    <#list items as item>
                    <tr>
                        <td>${escapeHtml(item.name)}</td>
                        <td>${item.qty}</td>
                        <td>${formatCurrency(item.price, defaultValue(currency, 'USD'))}</td>
                    </tr>
                    </#list>
                    </#if>
                </table>

                <p class="total">Total: ${formatCurrency(total, defaultValue(currency, 'USD'))}</p>
            </div>

            <#if deliveryDate??>
            <p><strong>Expected Delivery:</strong> ${formatDate(deliveryDate, 'MMMM dd, yyyy')}</p>
            </#if>

            <#if trackingUrl??>
            <p><a href="${trackingUrl}">Track Your Order</a></p>
            </#if>
        </div>
        <div class="footer">
            <p>Questions? Contact us at ${defaultValue(supportEmail, 'support@example.com')}</p>
            <p>&copy; ${.now?string('yyyy')} ${defaultValue(companyName, 'Our Company')}. All rights reserved.</p>
        </div>
    </div>
</body>
</html>
[/BODY]
