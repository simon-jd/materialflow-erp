import { Boxes, ClipboardCheck, LogOut, Package, ShoppingCart } from "lucide-react";
import { NavLink, Outlet } from "react-router";
import { useSession } from "@/auth/session-context";
import { shortId } from "@/lib/format";
import { cn } from "@/lib/utils";
import { Badge } from "./ui/badge";
import { Button } from "./ui/button";

const NAVIGATION = [
  { to: "/orders", label: "Orders", icon: ShoppingCart },
  { to: "/approvals", label: "Approvals", icon: ClipboardCheck },
  { to: "/inventory", label: "Inventory", icon: Package },
] as const;

export function AppLayout() {
  const session = useSession();
  const roles = [...session.roles].sort();
  return (
    <div className="min-h-dvh">
      <header className="border-b bg-card">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center gap-x-6 gap-y-2 px-6 py-3">
          <div className="flex items-center gap-2 font-semibold">
            <Boxes aria-hidden className="size-5" />
            MaterialFlow
          </div>
          <nav aria-label="Main" className="flex gap-1">
            {NAVIGATION.map(({ to, label, icon: Icon }) => (
              <NavLink
                key={to}
                to={to}
                className={({ isActive }) =>
                  cn(
                    "flex items-center gap-2 rounded-md px-3 py-1.5 text-sm text-muted-foreground transition-colors hover:text-foreground",
                    isActive && "bg-secondary text-foreground",
                  )
                }
              >
                <Icon aria-hidden className="size-4" />
                {label}
              </NavLink>
            ))}
          </nav>
          <div className="ml-auto flex items-center gap-3 text-sm">
            <div className="flex flex-col items-end">
              <span className="font-medium">{session.displayName}</span>
              <span className="text-xs text-muted-foreground">
                Organization {shortId(session.organizationId)}
              </span>
            </div>
            <div className="hidden gap-1 md:flex" aria-label="Roles">
              {roles.length === 0 ? (
                <Badge variant="outline">Read-only</Badge>
              ) : (
                roles.map((role) => (
                  <Badge key={role} variant="outline">
                    {role}
                  </Badge>
                ))
              )}
            </div>
            <Button variant="ghost" size="sm" onClick={session.signOut}>
              <LogOut aria-hidden />
              Sign out
            </Button>
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-7xl px-6 py-8">
        <Outlet />
      </main>
    </div>
  );
}
